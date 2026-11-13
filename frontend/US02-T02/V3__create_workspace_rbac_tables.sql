
ALTER TABLE identity.tenant_memberships
    ADD CONSTRAINT uq_tenant_memberships_tenant_membership UNIQUE (tenant_id, membership_id);

-- 1. WORKSPACES
CREATE TABLE identity.workspaces (
    workspace_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_at TIMESTAMPTZ,

    CONSTRAINT fk_workspaces_tenant FOREIGN KEY (tenant_id) REFERENCES identity.tenants (tenant_id),
    CONSTRAINT ck_workspaces_name_not_blank CHECK (BTRIM(name) <> ''),
    CONSTRAINT ck_workspaces_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT uq_workspaces_tenant_workspace UNIQUE (tenant_id, workspace_id)
);

CREATE TABLE identity.workspace_memberships (
    workspace_membership_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    tenant_membership_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    revision BIGINT NOT NULL DEFAULT 1,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,

    CONSTRAINT fk_wm_workspace FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES identity.workspaces (tenant_id, workspace_id),
    CONSTRAINT fk_wm_tenant_membership FOREIGN KEY (tenant_id, tenant_membership_id)
        REFERENCES identity.tenant_memberships (tenant_id, membership_id),
    CONSTRAINT ck_wm_status CHECK (status IN ('ACTIVE', 'ENDED')),
    CONSTRAINT ck_wm_revision CHECK (revision > 0),
    CONSTRAINT uq_wm_tenant_membership_id UNIQUE (tenant_id, workspace_membership_id)
);

CREATE UNIQUE INDEX uq_wm_one_active_per_workspace
    ON identity.workspace_memberships (workspace_id, tenant_membership_id)
    WHERE status = 'ACTIVE';

CREATE TABLE identity.workspace_role_assignments (
    assignment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    workspace_membership_id UUID NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,

    CONSTRAINT fk_wra_workspace_membership FOREIGN KEY (tenant_id, workspace_membership_id)
        REFERENCES identity.workspace_memberships (tenant_id, workspace_membership_id),
    CONSTRAINT ck_wra_role CHECK (role_code IN ('DEVELOPER', 'STAFF', 'MANAGER', 'TECHNICAL_LEAD')),
    CONSTRAINT ck_wra_revoked_after_granted CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
);

CREATE UNIQUE INDEX uq_wra_one_active_role
    ON identity.workspace_role_assignments (workspace_membership_id, role_code)
    WHERE revoked_at IS NULL;

CREATE TABLE identity.tenant_owner_grants (
    owner_grant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    tenant_membership_id UUID NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,

    CONSTRAINT fk_tog_tenant_membership FOREIGN KEY (tenant_id, tenant_membership_id)
        REFERENCES identity.tenant_memberships (tenant_id, membership_id),
    CONSTRAINT ck_tog_revoked_after_granted CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
);

CREATE UNIQUE INDEX uq_tog_one_active_grant
    ON identity.tenant_owner_grants (tenant_membership_id)
    WHERE revoked_at IS NULL;

ALTER TABLE identity.workspaces ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.workspace_memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.workspace_role_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.tenant_owner_grants ENABLE ROW LEVEL SECURITY;

CREATE POLICY workspaces_isolation ON identity.workspaces FOR ALL TO ld_s01_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

CREATE POLICY workspace_memberships_isolation ON identity.workspace_memberships FOR ALL TO ld_s01_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

CREATE POLICY workspace_role_assignments_isolation ON identity.workspace_role_assignments FOR ALL TO ld_s01_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

CREATE POLICY tenant_owner_grants_isolation ON identity.tenant_owner_grants FOR ALL TO ld_s01_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.workspaces TO ld_s01_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON identity.workspace_memberships TO ld_s01_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON identity.workspace_role_assignments TO ld_s01_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON identity.tenant_owner_grants TO ld_s01_app;
