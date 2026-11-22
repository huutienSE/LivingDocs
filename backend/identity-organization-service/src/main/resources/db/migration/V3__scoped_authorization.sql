-- LIV-138: scoped authority. V1/V2 remain immutable.
ALTER TABLE identity.tenants ADD COLUMN authz_revision BIGINT NOT NULL DEFAULT 1;
ALTER TABLE identity.tenants ADD CONSTRAINT ck_tenants_status CHECK (status IN ('ACTIVE', 'SUSPENDED'));
ALTER TABLE identity.tenant_memberships ADD CONSTRAINT ck_tenant_membership_status
    CHECK (status IN ('ACTIVE', 'REVOKED', 'ENDED'));
ALTER TABLE identity.tenant_memberships ADD CONSTRAINT ck_tenant_membership_end
    CHECK ((status = 'ACTIVE' AND ended_at IS NULL) OR (status <> 'ACTIVE' AND ended_at IS NOT NULL));
ALTER TABLE identity.tenant_memberships ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE identity.tenant_memberships ADD CONSTRAINT uq_tenant_membership_identity
    UNIQUE (tenant_id, membership_id, identity_id);
ALTER TABLE identity.tenant_memberships ADD CONSTRAINT uq_tenant_membership_scope
    UNIQUE (tenant_id, membership_id);
CREATE INDEX idx_tenant_membership_active_tenant
    ON identity.tenant_memberships (tenant_id, identity_id) WHERE status = 'ACTIVE';

-- Bootstrap is a SELECT-only, actor-scoped exception to the tenant policy in V2.
-- PostgreSQL combines permissive SELECT policies with OR. Only ACTIVE rows of the
-- verified principal become visible while app.identity_id is transaction-local.
CREATE POLICY active_membership_actor_lookup ON identity.tenant_memberships
    FOR SELECT TO PUBLIC
    USING (status = 'ACTIVE' AND identity_id =
        NULLIF(current_setting('app.identity_id', true), '')::uuid);

CREATE TABLE identity.workspaces (
    workspace_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES identity.tenants (tenant_id),
    name VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_workspace_name CHECK (BTRIM(name) <> ''),
    CONSTRAINT ck_workspace_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT uq_workspace_tenant UNIQUE (tenant_id, workspace_id)
);
CREATE INDEX idx_workspaces_active ON identity.workspaces (tenant_id, workspace_id)
    WHERE status = 'ACTIVE';

CREATE TABLE identity.workspace_memberships (
    workspace_membership_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    tenant_membership_id UUID NOT NULL,
    identity_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_workspace_membership_workspace FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES identity.workspaces (tenant_id, workspace_id),
    CONSTRAINT fk_workspace_membership_tenant_member FOREIGN KEY
        (tenant_id, tenant_membership_id, identity_id)
        REFERENCES identity.tenant_memberships (tenant_id, membership_id, identity_id),
    CONSTRAINT ck_workspace_membership_status CHECK (status IN ('ACTIVE', 'REVOKED', 'ENDED')),
    CONSTRAINT ck_workspace_membership_end CHECK
        ((status = 'ACTIVE' AND ended_at IS NULL) OR (status <> 'ACTIVE' AND ended_at IS NOT NULL)),
    CONSTRAINT uq_workspace_member_scope UNIQUE
        (tenant_id, workspace_id, workspace_membership_id)
);
CREATE UNIQUE INDEX uq_active_workspace_member ON identity.workspace_memberships
    (tenant_id, workspace_id, identity_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_workspace_memberships_identity ON identity.workspace_memberships
    (tenant_id, identity_id, workspace_id) WHERE status = 'ACTIVE';

CREATE TABLE identity.role_catalog (
    role_code VARCHAR(32) PRIMARY KEY,
    scope_type VARCHAR(16) NOT NULL,
    CONSTRAINT ck_role_scope CHECK (scope_type IN ('TENANT', 'WORKSPACE')),
    CONSTRAINT uq_role_scope UNIQUE (role_code, scope_type)
);
CREATE TABLE identity.permission_catalog (
    permission_code VARCHAR(64) PRIMARY KEY
);
CREATE TABLE identity.role_permissions (
    role_code VARCHAR(32) NOT NULL REFERENCES identity.role_catalog (role_code),
    permission_code VARCHAR(64) NOT NULL REFERENCES identity.permission_catalog (permission_code),
    PRIMARY KEY (role_code, permission_code)
);

INSERT INTO identity.role_catalog (role_code, scope_type) VALUES
    ('TENANT_OWNER', 'TENANT'), ('DEVELOPER', 'WORKSPACE'),
    ('STAFF', 'WORKSPACE'), ('MANAGER', 'WORKSPACE'),
    ('TECHNICAL_LEAD', 'WORKSPACE');
INSERT INTO identity.permission_catalog (permission_code) VALUES
    ('TENANT_ADMIN'), ('WORKSPACE_VIEW'), ('DOCUMENT_READ'),
    ('DOCUMENT_AUTHOR'), ('DOCUMENT_STAFF_REVIEW'),
    ('DOCUMENT_FINAL_APPROVE'), ('DOCUMENT_PUBLISH');
INSERT INTO identity.role_permissions (role_code, permission_code) VALUES
    ('TENANT_OWNER', 'TENANT_ADMIN'),
    ('DEVELOPER', 'WORKSPACE_VIEW'), ('DEVELOPER', 'DOCUMENT_READ'),
    ('DEVELOPER', 'DOCUMENT_AUTHOR'),
    ('STAFF', 'WORKSPACE_VIEW'), ('STAFF', 'DOCUMENT_READ'),
    ('STAFF', 'DOCUMENT_STAFF_REVIEW'),
    ('MANAGER', 'WORKSPACE_VIEW'), ('MANAGER', 'DOCUMENT_READ'),
    ('MANAGER', 'DOCUMENT_FINAL_APPROVE'), ('MANAGER', 'DOCUMENT_PUBLISH'),
    ('TECHNICAL_LEAD', 'WORKSPACE_VIEW'), ('TECHNICAL_LEAD', 'DOCUMENT_READ');

CREATE TABLE identity.tenant_role_assignments (
    assignment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    tenant_membership_id UUID NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    role_scope VARCHAR(16) NOT NULL DEFAULT 'TENANT' CHECK (role_scope = 'TENANT'),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_tenant_role_membership FOREIGN KEY (tenant_id, tenant_membership_id)
        REFERENCES identity.tenant_memberships (tenant_id, membership_id),
    CONSTRAINT fk_tenant_role_catalog FOREIGN KEY (role_code, role_scope)
        REFERENCES identity.role_catalog (role_code, scope_type),
    CONSTRAINT ck_tenant_role_end CHECK
        ((status = 'ACTIVE' AND ended_at IS NULL) OR (status = 'REVOKED' AND ended_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_active_tenant_role ON identity.tenant_role_assignments
    (tenant_id, tenant_membership_id, role_code) WHERE status = 'ACTIVE';

CREATE TABLE identity.workspace_role_assignments (
    assignment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    workspace_membership_id UUID NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    role_scope VARCHAR(16) NOT NULL DEFAULT 'WORKSPACE' CHECK (role_scope = 'WORKSPACE'),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_workspace_role_membership FOREIGN KEY
        (tenant_id, workspace_id, workspace_membership_id)
        REFERENCES identity.workspace_memberships
        (tenant_id, workspace_id, workspace_membership_id),
    CONSTRAINT fk_workspace_role_catalog FOREIGN KEY (role_code, role_scope)
        REFERENCES identity.role_catalog (role_code, scope_type),
    CONSTRAINT ck_workspace_role_end CHECK
        ((status = 'ACTIVE' AND ended_at IS NULL) OR (status = 'REVOKED' AND ended_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_active_workspace_role ON identity.workspace_role_assignments
    (tenant_id, workspace_membership_id, role_code) WHERE status = 'ACTIVE';

-- Repository IDs are S02 logical references. PENDING grants are never effective.
CREATE TABLE identity.repository_grants (
    grant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    workspace_membership_id UUID NOT NULL,
    repository_connection_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    verification_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (verification_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    verification_source VARCHAR(24)
        CHECK (verification_source IN ('S02', 'OPERATOR_ATTESTED')),
    provenance TEXT NOT NULL,
    operator_identity_id UUID NOT NULL REFERENCES identity.identities (identity_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    verified_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_repository_grant_workspace_member FOREIGN KEY
        (tenant_id, workspace_id, workspace_membership_id)
        REFERENCES identity.workspace_memberships
        (tenant_id, workspace_id, workspace_membership_id),
    CONSTRAINT ck_repository_grant_verification CHECK
        ((verification_status = 'VERIFIED' AND verification_source IS NOT NULL AND verified_at IS NOT NULL)
        OR (verification_status <> 'VERIFIED' AND verified_at IS NULL)),
    CONSTRAINT ck_repository_grant_end CHECK
        ((status = 'ACTIVE' AND ended_at IS NULL) OR (status = 'REVOKED' AND ended_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_active_repository_grant ON identity.repository_grants
    (tenant_id, workspace_membership_id, repository_connection_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_effective_repository_grants ON identity.repository_grants
    (tenant_id, workspace_id, workspace_membership_id, repository_connection_id)
    WHERE status = 'ACTIVE' AND verification_status = 'VERIFIED';

CREATE TABLE identity.authorization_audit (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    authz_revision BIGINT NOT NULL,
    actor_identity_id UUID,
    db_actor_name TEXT NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_table VARCHAR(64) NOT NULL,
    target_id UUID NOT NULL,
    reason TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_authorization_audit_tenant_revision
    ON identity.authorization_audit (tenant_id, authz_revision, occurred_at);

CREATE TABLE identity.provisioning_operations (
    operation_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    operator_identity_id UUID NOT NULL REFERENCES identity.identities (identity_id),
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Row-level changes, including CLI writes, invalidate previous authority snapshots.
-- The trigger is narrowly privileged: fixed schema, no dynamic SQL, no cross-tenant return.
CREATE FUNCTION identity.record_authority_change() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, identity AS $$
DECLARE
    affected_tenant UUID;
    changed_id UUID;
    next_revision BIGINT;
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
    UPDATE identity.tenants SET authz_revision = authz_revision + 1
        WHERE tenant_id = affected_tenant RETURNING authz_revision INTO next_revision;
    INSERT INTO identity.authorization_audit
        (tenant_id, authz_revision, actor_identity_id, db_actor_name, action, target_table, target_id, reason)
    VALUES (affected_tenant, next_revision,
        NULLIF(current_setting('app.operator_identity_id', true), '')::uuid, session_user,
        TG_OP, TG_TABLE_NAME, changed_id,
        NULLIF(current_setting('app.change_reason', true), ''));
    RETURN NULL; -- AFTER trigger return value is ignored.
END $$;
REVOKE ALL ON FUNCTION identity.record_authority_change() FROM PUBLIC;

CREATE TRIGGER audit_tenant_membership AFTER INSERT OR UPDATE OR DELETE
    ON identity.tenant_memberships FOR EACH ROW EXECUTE FUNCTION identity.record_authority_change();
CREATE TRIGGER audit_workspace AFTER INSERT OR UPDATE OR DELETE
    ON identity.workspaces FOR EACH ROW EXECUTE FUNCTION identity.record_authority_change();
CREATE TRIGGER audit_workspace_membership AFTER INSERT OR UPDATE OR DELETE
    ON identity.workspace_memberships FOR EACH ROW EXECUTE FUNCTION identity.record_authority_change();
CREATE TRIGGER audit_tenant_role AFTER INSERT OR UPDATE OR DELETE
    ON identity.tenant_role_assignments FOR EACH ROW EXECUTE FUNCTION identity.record_authority_change();
CREATE TRIGGER audit_workspace_role AFTER INSERT OR UPDATE OR DELETE
    ON identity.workspace_role_assignments FOR EACH ROW EXECUTE FUNCTION identity.record_authority_change();
CREATE TRIGGER audit_repository_grant AFTER INSERT OR UPDATE OR DELETE
    ON identity.repository_grants FOR EACH ROW EXECUTE FUNCTION identity.record_authority_change();

CREATE FUNCTION identity.record_tenant_status_change() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, identity AS $$
DECLARE next_revision BIGINT;
BEGIN
    UPDATE identity.tenants SET authz_revision = authz_revision + 1
        WHERE tenant_id = NEW.tenant_id RETURNING authz_revision INTO next_revision;
    INSERT INTO identity.authorization_audit
        (tenant_id, authz_revision, actor_identity_id, db_actor_name, action, target_table, target_id, reason)
    VALUES (NEW.tenant_id, next_revision,
        NULLIF(current_setting('app.operator_identity_id', true), '')::uuid, session_user,
        'STATUS_CHANGE', 'tenants', NEW.tenant_id,
        NULLIF(current_setting('app.change_reason', true), ''));
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION identity.record_tenant_status_change() FROM PUBLIC;
CREATE TRIGGER audit_tenant_status AFTER UPDATE OF status ON identity.tenants
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION identity.record_tenant_status_change();

CREATE FUNCTION identity.record_identity_status_change() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, identity AS $$
DECLARE active_tenant UUID;
DECLARE next_revision BIGINT;
BEGIN
    SELECT tenant_id INTO active_tenant FROM identity.tenant_memberships
        WHERE identity_id = NEW.identity_id AND status = 'ACTIVE';
    IF active_tenant IS NOT NULL THEN
        UPDATE identity.tenants SET authz_revision = authz_revision + 1
            WHERE tenant_id = active_tenant RETURNING authz_revision INTO next_revision;
        INSERT INTO identity.authorization_audit
            (tenant_id, authz_revision, actor_identity_id, db_actor_name, action, target_table, target_id, reason)
        VALUES (active_tenant, next_revision,
            NULLIF(current_setting('app.operator_identity_id', true), '')::uuid, session_user,
            'STATUS_CHANGE', 'identities', NEW.identity_id,
            NULLIF(current_setting('app.change_reason', true), ''));
    END IF;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION identity.record_identity_status_change() FROM PUBLIC;
CREATE TRIGGER audit_identity_status AFTER UPDATE OF status ON identity.identities
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION identity.record_identity_status_change();

-- Every tenant-scoped authority table denies access without app.tenant_id.
ALTER TABLE identity.workspaces ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.workspace_memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.tenant_role_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.workspace_role_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.repository_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.authorization_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.provisioning_operations ENABLE ROW LEVEL SECURITY;
-- Table grants still gate each role. PUBLIC policies let the separately provisioned
-- operator DB role use the same tenant boundary without an application bypass.
CREATE POLICY tenant_operator_scope ON identity.tenants FOR ALL TO PUBLIC
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_membership_operator_scope ON identity.tenant_memberships FOR ALL TO PUBLIC
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY workspace_isolation ON identity.workspaces FOR ALL TO PUBLIC
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY workspace_membership_isolation ON identity.workspace_memberships FOR ALL TO PUBLIC
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_role_isolation ON identity.tenant_role_assignments FOR ALL TO PUBLIC
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY workspace_role_isolation ON identity.workspace_role_assignments FOR ALL TO PUBLIC
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY repository_grant_isolation ON identity.repository_grants FOR ALL TO PUBLIC
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY authorization_audit_isolation ON identity.authorization_audit FOR ALL TO PUBLIC
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY provisioning_operations_isolation ON identity.provisioning_operations FOR ALL TO PUBLIC
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- Public S01 runtime only reads authority in PR 1. V2 granted writes on the two
-- pre-existing tables; remove them so provisioning is exclusive to its DB role.
REVOKE INSERT, UPDATE, DELETE ON identity.tenants, identity.tenant_memberships
    FROM ld_s01_app;
GRANT SELECT ON identity.workspaces, identity.workspace_memberships,
    identity.tenant_role_assignments, identity.workspace_role_assignments,
    identity.repository_grants TO ld_s01_app;
GRANT SELECT ON identity.role_catalog, identity.permission_catalog,
    identity.role_permissions, identity.authorization_audit TO ld_s01_app;

-- The operator role is normally installed after V3 by provisioner-role.sql.
-- Testcontainers may pre-create it; this optional grant keeps V3 independent
-- of that operational ordering while retaining identical table privileges.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ld_s01_provisioner') THEN
        GRANT SELECT ON identity.identities, identity.role_catalog,
            identity.permission_catalog, identity.role_permissions,
            identity.authorization_audit TO ld_s01_provisioner;
        GRANT SELECT, INSERT, UPDATE ON identity.tenants, identity.tenant_memberships,
            identity.workspaces, identity.workspace_memberships,
            identity.tenant_role_assignments, identity.workspace_role_assignments,
            identity.repository_grants TO ld_s01_provisioner;
        GRANT SELECT, INSERT ON identity.provisioning_operations TO ld_s01_provisioner;
    END IF;
END $$;
