-- LIV-162. The DBA creates ld_s01_http_writer before this migration.
-- This migration deliberately preserves operator_identity_id and all historical grants.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ld_s01_http_writer') THEN
        RAISE EXCEPTION 'Run infrastructure/docker/http-writer-role.sql as DBA before S01 V7';
    END IF;
END $$;

ALTER TABLE identity.repository_grants
    ADD COLUMN verified_binding_revision BIGINT; --  bổ sung sửa đổi liên kết
ALTER TABLE identity.repository_grants
    ADD CONSTRAINT ck_grant_binding_revision_positive
    CHECK (verified_binding_revision IS NULL OR verified_binding_revision > 0);

-- Legacy VERIFIED rows are retained, but are ineffective until S02 re-verification.
CREATE TABLE identity.authority_http_operations (
    operation_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    actor_identity_id UUID NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    result_grant_id UUID,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_http_operation_actor FOREIGN KEY (actor_identity_id)
        REFERENCES identity.identities(identity_id)
);
ALTER TABLE identity.authority_http_operations ENABLE ROW LEVEL SECURITY;
CREATE POLICY http_operation_tenant_scope ON identity.authority_http_operations FOR ALL TO PUBLIC
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- The application login inherits no authority DML. The switched role has only
-- the reads needed by shared domain checks and writes needed by PR3.
GRANT USAGE ON SCHEMA identity TO ld_s01_http_writer;
GRANT SELECT ON identity.identities, identity.tenants, identity.tenant_memberships,
    identity.workspaces, identity.workspace_memberships, identity.tenant_role_assignments,
    identity.workspace_role_assignments, identity.role_catalog, identity.role_permissions,
    identity.repository_grants, identity.authority_http_operations TO ld_s01_http_writer;
GRANT INSERT, UPDATE (status, verification_status, verification_source,
    verified_binding_revision, verified_at, ended_at, row_version)
    ON identity.repository_grants TO ld_s01_http_writer;
GRANT INSERT, UPDATE (result_grant_id) ON identity.authority_http_operations TO ld_s01_http_writer;

-- Distinguish service invalidation from a human request without changing old rows.
ALTER TABLE identity.authorization_audit ADD COLUMN actor_kind VARCHAR(16);
ALTER TABLE identity.authorization_audit ADD COLUMN actor_ref VARCHAR(128);

CREATE OR REPLACE FUNCTION identity.record_authority_change() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, identity, pg_temp AS $$
DECLARE affected_tenant UUID; changed_id UUID; next_revision BIGINT; actor UUID;
        kind TEXT; ref TEXT;
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
        kind := 'OPERATOR'; ref := session_user;
    ELSIF session_user = 'ld_s01_app' AND TG_TABLE_NAME = 'repository_grants' THEN
        IF affected_tenant IS DISTINCT FROM NULLIF(current_setting('app.tenant_id', true), '')::uuid THEN
            RAISE EXCEPTION 'Scoped HTTP writer required';
        END IF;
        kind := NULLIF(current_setting('app.actor_kind', true), '');
        ref := NULLIF(current_setting('app.actor_ref', true), '');
        IF kind = 'USER' THEN
            IF actor IS NULL OR ref IS DISTINCT FROM actor::text OR NOT EXISTS
                (SELECT 1 FROM identity.identities i WHERE i.identity_id = actor AND i.status = 'ACTIVE') THEN
                RAISE EXCEPTION 'Authenticated HTTP actor required';
            END IF;
        ELSE
            RAISE EXCEPTION 'HTTP actor kind required';
        END IF;
    ELSE
        kind := 'DBA'; ref := session_user;
    END IF;
    UPDATE identity.tenants SET authz_revision = authz_revision + 1
        WHERE tenant_id = affected_tenant RETURNING authz_revision INTO next_revision;
    INSERT INTO identity.authorization_audit
        (tenant_id, authz_revision, actor_identity_id, db_actor_name, action,
         target_table, target_id, reason, actor_kind, actor_ref)
    VALUES (affected_tenant, next_revision, actor, session_user, TG_OP,
            TG_TABLE_NAME, changed_id, NULLIF(current_setting('app.change_reason', true), ''), kind, ref);
    RETURN NULL;
END $$;
