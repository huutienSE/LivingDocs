-- LIV-114 - S01 Tenant and Membership
-- TENANTS:
-- Luu thong tin to chuc su dung LivingDocs.
-- Moi Tenant la pham vi so huu du lieu rieng.
CREATE TABLE identity.tenants(
    tenant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',

    create_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_tenants_name_not_blank CHECK (BTRIM(name) <> '')
);

-- TENANT_MEMBERSHIPS:
-- Lien ket Identity voi Tenant va luu lich su tham gia.
-- Moi Identity chi duoc co toi da 1 membership ACTIVE.
-- Roi Tenant khong duoc xoa lich su membership.
CREATE TABLE identity.tenant_memberships (
    membership_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    tenant_id UUID NOT NULL,
    identity_id UUID NOT NULL,

    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',

    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,

    CONSTRAINT fk_membership_tenant FOREIGN KEY (tenant_id) REFERENCES identity.tenants(tenant_id),

    CONSTRAINT fk_membership_identity FOREIGN KEY (identity_id) REFERENCES identity.identities(identity_id)
);

-- One identity can have at most one ACTIVE tenant membership
CREATE UNIQUE INDEX uq_one_active_tenant_membership
    ON identity.tenant_memberships(identity_id)
    WHERE status = 'ACTIVE';


-- LIV-114 - S01 Tenant Isolation (PostgreSQL RLS)

-- 1. Enable Row-Level Security
ALTER TABLE identity.tenants
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE identity.tenant_memberships
    ENABLE ROW LEVEL SECURITY;

-- 2. Tenant isolation policy
CREATE POLICY tenants_isolation
ON identity.tenants
FOR ALL
TO ld_s01_app
USING (
    tenant_id = NULLIF(
        current_setting('app.tenant_id', true), ''
    )::UUID
)
WITH CHECK (
    tenant_id = NULLIF(
        current_setting('app.tenant_id', true), ''
    )::UUID
);

-- 3. Tenant Membership isolation policy
CREATE POLICY tenant_memberships_isolation
ON identity.tenant_memberships
FOR ALL
TO ld_s01_app
USING (
    tenant_id = NULLIF(
        current_setting('app.tenant_id', true), ''
    )::UUID
)
WITH CHECK (
    tenant_id = NULLIF(
        current_setting('app.tenant_id', true), ''
    )::UUID
);

-- 4. Grant business table privileges to runtime role
GRANT SELECT, INSERT, UPDATE, DELETE
    ON identity.tenants
    TO ld_s01_app;

GRANT SELECT, INSERT, UPDATE, DELETE
    ON identity.tenant_memberships
    TO ld_s01_app;
