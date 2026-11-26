DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ld_s03_app') THEN
        CREATE ROLE ld_s03_app LOGIN NOBYPASSRLS PASSWORD 'test_app_password';
    END IF;
END
$$;

CREATE SCHEMA IF NOT EXISTS intelligence AUTHORIZATION postgres;
GRANT USAGE ON SCHEMA intelligence TO ld_s03_app;
