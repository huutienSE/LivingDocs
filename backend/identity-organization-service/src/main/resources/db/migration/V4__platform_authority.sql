-- LIV-138 Flow 1. V3 is preserved because its deployment history cannot be verified here.
ALTER TABLE identity.role_catalog DROP CONSTRAINT ck_role_scope;
ALTER TABLE identity.role_catalog ADD CONSTRAINT ck_role_scope
    CHECK (scope_type IN ('PLATFORM', 'TENANT', 'WORKSPACE'));
INSERT INTO identity.role_catalog(role_code, scope_type) VALUES ('PLATFORM_ADMIN', 'PLATFORM');
INSERT INTO identity.permission_catalog(permission_code) VALUES
    ('PLATFORM_TENANT_PROVISION'), ('PLATFORM_INITIAL_OWNER_ASSIGN');
INSERT INTO identity.role_permissions(role_code, permission_code) VALUES
    ('PLATFORM_ADMIN', 'PLATFORM_TENANT_PROVISION'),
    ('PLATFORM_ADMIN', 'PLATFORM_INITIAL_OWNER_ASSIGN');

CREATE TABLE identity.platform_role_assignments (
    assignment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    identity_id UUID NOT NULL REFERENCES identity.identities(identity_id),
    role_code VARCHAR(32) NOT NULL,
    role_scope VARCHAR(16) NOT NULL DEFAULT 'PLATFORM' CHECK (role_scope = 'PLATFORM'),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_platform_role_catalog FOREIGN KEY(role_code, role_scope)
        REFERENCES identity.role_catalog(role_code, scope_type),
    CONSTRAINT ck_platform_role_end CHECK
        ((status = 'ACTIVE' AND ended_at IS NULL) OR (status = 'REVOKED' AND ended_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_active_platform_role ON identity.platform_role_assignments(identity_id, role_code)
    WHERE status = 'ACTIVE';

-- ánh xạ tài khaonr đănge nhập postgre của operator với livingdocs identity
CREATE TABLE identity.operator_principals (
    db_login_name VARCHAR(63) PRIMARY KEY,
    identity_id UUID NOT NULL UNIQUE REFERENCES identity.identities(identity_id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_operator_principal_end CHECK
        ((status = 'ACTIVE' AND revoked_at IS NULL) OR (status = 'REVOKED' AND revoked_at IS NOT NULL))
);
ALTER TABLE identity.operator_principals ENABLE ROW LEVEL SECURITY;
CREATE POLICY operator_principal_self ON identity.operator_principals
    FOR SELECT TO PUBLIC USING (db_login_name = session_user);

-- theo dói bootstrap đã được sưer dụng chưa và revision của authority cấp platform
CREATE TABLE identity.platform_authority_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton),
    bootstrap_used_at TIMESTAMPTZ,
    authz_revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO identity.platform_authority_state(singleton) VALUES (TRUE);

-- Ghi lịch sử thay đổi Platform Authority
CREATE TABLE identity.platform_authorization_audit (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    authz_revision BIGINT NOT NULL,
    actor_identity_id UUID,
    db_actor_name TEXT NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_identity_id UUID NOT NULL,
    reason TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE FUNCTION identity.record_platform_authority_change() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, identity, pg_temp AS $$
DECLARE subject UUID; revision BIGINT;
BEGIN
    subject := CASE WHEN TG_OP = 'DELETE' THEN OLD.identity_id ELSE NEW.identity_id END;
    UPDATE identity.platform_authority_state SET authz_revision = authz_revision + 1
        WHERE singleton RETURNING authz_revision INTO revision;
    INSERT INTO identity.platform_authorization_audit
        (authz_revision, actor_identity_id, db_actor_name, action, target_identity_id, reason)
    VALUES (revision, NULLIF(current_setting('app.actor_identity_id', true), '')::uuid,
            session_user, TG_TABLE_NAME || '_' || TG_OP, subject,
            COALESCE(NULLIF(current_setting('app.change_reason', true), ''), 'DBA-controlled authority change'));
    RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION identity.record_platform_authority_change() FROM PUBLIC;
CREATE TRIGGER audit_platform_assignment AFTER INSERT OR UPDATE OR DELETE
    ON identity.platform_role_assignments FOR EACH ROW
    EXECUTE FUNCTION identity.record_platform_authority_change();
CREATE TRIGGER audit_operator_principal AFTER INSERT OR UPDATE OR DELETE
    ON identity.operator_principals FOR EACH ROW
    EXECUTE FUNCTION identity.record_platform_authority_change();

-- A distinct, one-use trust root. The DBA grants EXECUTE only to a short-lived
-- bootstrap login after migration; the function never accepts a tenant selector.
--
CREATE FUNCTION identity.bootstrap_platform_admin(initial_identity UUID, audit_reason TEXT)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, identity, pg_temp AS $$
DECLARE new_assignment UUID;
BEGIN
    IF NOT pg_has_role(session_user, 'ld_s01_bootstrap', 'member')
       OR (SELECT rolsuper FROM pg_roles WHERE rolname = session_user) THEN
        RAISE EXCEPTION 'Named bootstrap database principal required';
    END IF;
    IF initial_identity IS NULL OR audit_reason IS NULL OR length(btrim(audit_reason)) < 8 THEN
        RAISE EXCEPTION 'Initial identity and reason are required';
    END IF;
    PERFORM 1 FROM identity.platform_authority_state WHERE singleton FOR UPDATE;
    IF (SELECT bootstrap_used_at IS NOT NULL FROM identity.platform_authority_state WHERE singleton)
       OR EXISTS (SELECT 1 FROM identity.platform_role_assignments WHERE status = 'ACTIVE') THEN
        RAISE EXCEPTION 'Platform bootstrap already consumed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM identity.identities
                   WHERE identity_id = initial_identity AND status = 'ACTIVE') THEN
        RAISE EXCEPTION 'Active identity required';
    END IF;
    PERFORM set_config('app.change_reason', audit_reason, true);
    INSERT INTO identity.platform_role_assignments(identity_id, role_code)
    VALUES (initial_identity, 'PLATFORM_ADMIN') RETURNING assignment_id INTO new_assignment;
    UPDATE identity.platform_authority_state
    SET bootstrap_used_at = CURRENT_TIMESTAMP WHERE singleton;
    RETURN new_assignment;
END $$;
REVOKE ALL ON FUNCTION identity.bootstrap_platform_admin(UUID, TEXT) FROM PUBLIC;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ld_s01_bootstrap') THEN
        GRANT EXECUTE ON FUNCTION identity.bootstrap_platform_admin(UUID, TEXT) TO ld_s01_bootstrap;
    END IF;
END $$;

CREATE FUNCTION identity.record_tenant_creation() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, identity, pg_temp AS $$
DECLARE next_revision BIGINT; actor UUID;
BEGIN
    actor := NULLIF(current_setting('app.actor_identity_id', true), '')::uuid;
    IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname = session_user)
       AND pg_has_role(session_user, 'ld_s01_provisioner', 'member') THEN
        IF actor IS NULL OR NOT EXISTS
            (SELECT 1 FROM identity.operator_principals p WHERE p.db_login_name = session_user
             AND p.identity_id = actor AND p.status = 'ACTIVE') THEN
            RAISE EXCEPTION 'Authenticated operator actor required';
        END IF;
    END IF;
    UPDATE identity.tenants SET authz_revision = authz_revision + 1
        WHERE tenant_id = NEW.tenant_id RETURNING authz_revision INTO next_revision;
    INSERT INTO identity.authorization_audit
        (tenant_id, authz_revision, actor_identity_id, db_actor_name, action, target_table, target_id, reason)
    VALUES (NEW.tenant_id, next_revision,
        actor, session_user,
        'CREATE', 'tenants', NEW.tenant_id,
        NULLIF(current_setting('app.change_reason', true), ''));
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION identity.record_tenant_creation() FROM PUBLIC;
CREATE TRIGGER audit_tenant_create AFTER INSERT ON identity.tenants
    FOR EACH ROW EXECUTE FUNCTION identity.record_tenant_creation();

-- Platform data has no tenant_id. Runtime application can only read assignments
-- for the verified JWT subject after SET LOCAL app.identity_id.
ALTER TABLE identity.platform_role_assignments ENABLE ROW LEVEL SECURITY;
CREATE POLICY platform_assignment_self ON identity.platform_role_assignments
    FOR SELECT TO ld_s01_app
    USING (identity_id = NULLIF(current_setting('app.identity_id', true), '')::uuid);
GRANT SELECT ON identity.platform_role_assignments TO ld_s01_app;
-- The CLI group role reads platform assignments; operator identity is resolved
-- from session_user by S01, not supplied by the manifest.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ld_s01_provisioner') THEN
        GRANT SELECT ON identity.platform_role_assignments, identity.operator_principals
            TO ld_s01_provisioner;
    END IF;
END $$;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ld_s01_provisioner') THEN
        EXECUTE 'CREATE POLICY platform_assignment_operator ON identity.platform_role_assignments
            FOR SELECT TO ld_s01_provisioner
            USING (identity_id = (SELECT p.identity_id FROM identity.operator_principals p
                   WHERE p.db_login_name = session_user AND p.status = ''ACTIVE''))';
    END IF;
END $$;

-- Existing V3 audit is retained. New CLI writes use the verified actor GUC;
-- direct privileged DBA maintenance remains traceable by db_actor_name.
CREATE OR REPLACE FUNCTION identity.record_authority_change() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, identity, pg_temp AS $$
DECLARE affected_tenant UUID; changed_id UUID; next_revision BIGINT; actor UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        affected_tenant := OLD.tenant_id;
        changed_id := (to_jsonb(OLD) ->> CASE TG_TABLE_NAME
            WHEN 'tenant_memberships' THEN 'membership_id'
            WHEN 'workspaces' THEN 'workspace_id'
            WHEN 'workspace_memberships' THEN 'workspace_membership_id'
            WHEN 'tenant_role_assignments' THEN 'assignment_id'
            WHEN 'workspace_role_assignments' THEN 'assignment_id'
            ELSE 'grant_id' END)::uuid;
    ELSE
        affected_tenant := NEW.tenant_id;
        changed_id := (to_jsonb(NEW) ->> CASE TG_TABLE_NAME
            WHEN 'tenant_memberships' THEN 'membership_id'
            WHEN 'workspaces' THEN 'workspace_id'
            WHEN 'workspace_memberships' THEN 'workspace_membership_id'
            WHEN 'tenant_role_assignments' THEN 'assignment_id'
            WHEN 'workspace_role_assignments' THEN 'assignment_id'
            ELSE 'grant_id' END)::uuid;
    END IF;
    actor := NULLIF(current_setting('app.actor_identity_id', true), '')::uuid;
    IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname = session_user)
       AND pg_has_role(session_user, 'ld_s01_provisioner', 'member') THEN
        IF actor IS NULL OR NOT EXISTS
            (SELECT 1 FROM identity.operator_principals p WHERE p.db_login_name = session_user
             AND p.identity_id = actor AND p.status = 'ACTIVE') THEN
            RAISE EXCEPTION 'Authenticated operator actor required';
        END IF;
    END IF;
    UPDATE identity.tenants SET authz_revision = authz_revision + 1
        WHERE tenant_id = affected_tenant RETURNING authz_revision INTO next_revision;
    INSERT INTO identity.authorization_audit
        (tenant_id, authz_revision, actor_identity_id, db_actor_name, action, target_table, target_id, reason)
    VALUES (affected_tenant, next_revision, actor, session_user,
            TG_OP, TG_TABLE_NAME, changed_id, NULLIF(current_setting('app.change_reason', true), ''));
    RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION identity.record_tenant_status_change() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, identity, pg_temp AS $$
DECLARE next_revision BIGINT;
BEGIN
    UPDATE identity.tenants SET authz_revision = authz_revision + 1
        WHERE tenant_id = NEW.tenant_id RETURNING authz_revision INTO next_revision;
    INSERT INTO identity.authorization_audit
        (tenant_id, authz_revision, actor_identity_id, db_actor_name, action, target_table, target_id, reason)
    VALUES (NEW.tenant_id, next_revision,
        NULLIF(current_setting('app.actor_identity_id', true), '')::uuid, session_user,
        'STATUS_CHANGE', 'tenants', NEW.tenant_id,
        NULLIF(current_setting('app.change_reason', true), ''));
    RETURN NEW;
END $$;
