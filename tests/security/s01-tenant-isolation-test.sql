
-- LIV-114 - S01 Tenant Isolation Verification
-- Run as: ld_s01_app
-- Prerequisite: Test tenants A/B and memberships
-- have been seeded in the local test database.
-- Never run against production data.

BEGIN;

DO $test$
DECLARE
tenant_a UUID :=
        'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
    tenant_b UUID :=
        'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
BEGIN
    -- Verify the connection is using the runtime role
    IF current_user <> 'ld_s01_app' THEN
        RAISE EXCEPTION 'Wrong DB role: %', current_user;
END IF;

    -- TEST 1: No tenant context -> no rows
    PERFORM set_config('app.tenant_id', '', true);

    IF EXISTS (SELECT 1 FROM identity.tenants)
       OR EXISTS (SELECT 1 FROM identity.tenant_memberships)
    THEN
        RAISE EXCEPTION 'FAIL: Missing tenant context';
END IF;

    -- TEST 2: Tenant A can only see A
    PERFORM set_config('app.tenant_id', tenant_a::TEXT, true);

    IF (SELECT COUNT(*) FROM identity.tenants) <> 1
       OR EXISTS (
           SELECT 1 FROM identity.tenants
           WHERE tenant_id <> tenant_a
       )
       OR (SELECT COUNT(*)
           FROM identity.tenant_memberships) <> 1
       OR EXISTS (
           SELECT 1 FROM identity.tenant_memberships
           WHERE tenant_id <> tenant_a
       )
    THEN
        RAISE EXCEPTION 'FAIL: Tenant A isolation';
END IF;

    -- TEST 3: Tenant B can only see B
    PERFORM set_config('app.tenant_id', tenant_b::TEXT, true);

    IF (SELECT COUNT(*) FROM identity.tenants) <> 1
       OR EXISTS (
           SELECT 1 FROM identity.tenants
           WHERE tenant_id <> tenant_b
       )
       OR (SELECT COUNT(*)
           FROM identity.tenant_memberships) <> 1
       OR EXISTS (
           SELECT 1 FROM identity.tenant_memberships
           WHERE tenant_id <> tenant_b
       )
    THEN
        RAISE EXCEPTION 'FAIL: Tenant B isolation';
END IF;

    -- TEST 4: Tenant A cannot insert into Tenant B
    PERFORM set_config('app.tenant_id', tenant_a::TEXT, true);

    IF NOT has_table_privilege(
        'identity.tenant_memberships', 'INSERT'
    ) THEN
        RAISE EXCEPTION 'FAIL: Missing INSERT privilege';
END IF;

BEGIN
INSERT INTO identity.tenant_memberships (
    membership_id, tenant_id, identity_id,
    status, ended_at
)
VALUES (
           gen_random_uuid(),
           tenant_b,
           '22222222-2222-4222-8222-222222222222',
           'ENDED',
           CURRENT_TIMESTAMP
       );

RAISE EXCEPTION 'FAIL: Cross-tenant INSERT allowed';

EXCEPTION
        WHEN insufficient_privilege THEN
            RAISE NOTICE 'PASS: Cross-tenant INSERT denied';
END;

    RAISE NOTICE 'PASS: S01 tenant isolation tests';
END
$test$;

-- Never persist test changes
ROLLBACK;
