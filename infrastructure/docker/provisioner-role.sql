-- Run as DBA after Flyway V4. This is a NOLOGIN privilege group; every
-- operator receives a separate LOGIN via operator-enroll.sql.
-- Thiết lập nhóm quyền DB phục vụ provisioning/bootstrap
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ld_s01_provisioner') THEN
        CREATE ROLE ld_s01_provisioner NOLOGIN NOBYPASSRLS;
    END IF;
END $$;
ALTER ROLE ld_s01_provisioner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO ld_s01_provisioner', current_database());
END $$;
GRANT USAGE ON SCHEMA identity TO ld_s01_provisioner;
GRANT SELECT ON identity.identities, identity.role_catalog,
    identity.permission_catalog, identity.role_permissions,
    identity.authorization_audit TO ld_s01_provisioner;
GRANT SELECT, INSERT, UPDATE ON identity.tenants, identity.tenant_memberships,
    identity.workspaces, identity.workspace_memberships,
    identity.tenant_role_assignments, identity.workspace_role_assignments,
    identity.repository_grants TO ld_s01_provisioner;
GRANT SELECT, INSERT ON identity.provisioning_operations TO ld_s01_provisioner;
GRANT SELECT ON identity.platform_role_assignments, identity.operator_principals
    TO ld_s01_provisioner;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'identity'
       AND tablename = 'platform_role_assignments' AND policyname = 'platform_assignment_operator') THEN
        CREATE POLICY platform_assignment_operator ON identity.platform_role_assignments
            FOR SELECT TO ld_s01_provisioner
            USING (identity_id = (SELECT p.identity_id FROM identity.operator_principals p
                WHERE p.db_login_name = session_user AND p.status = 'ACTIVE'));
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ld_s01_bootstrap') THEN
        CREATE ROLE ld_s01_bootstrap NOLOGIN NOBYPASSRLS;
    END IF;
END $$;
ALTER ROLE ld_s01_bootstrap NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA identity TO ld_s01_bootstrap;
GRANT EXECUTE ON FUNCTION identity.bootstrap_platform_admin(UUID, TEXT) TO ld_s01_bootstrap;
DO $$ BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO ld_s01_bootstrap', current_database());
END $$;
