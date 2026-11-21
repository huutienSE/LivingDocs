-- Run as DBA with -v bootstrap_login=ld_bootstrap_<name>; password via secret store.
-- Chuẩn bị DB login tạm thời có quyền bootstrap
\set ON_ERROR_STOP on
BEGIN;
SELECT 1 / (CASE WHEN :'bootstrap_login' ~ '^ld_bootstrap_[a-z0-9_]{3,40}$'
    THEN 1 ELSE 0 END) AS valid_login_required;
SELECT format('CREATE ROLE %I LOGIN NOBYPASSRLS', :'bootstrap_login')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'bootstrap_login') \gexec
ALTER ROLE :"bootstrap_login" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT ld_s01_bootstrap TO :"bootstrap_login" WITH INHERIT TRUE, SET FALSE;
COMMIT;
