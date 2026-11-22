-- psql -v operator_login=... -v operator_identity_id=... -v reason=... -f operator-enroll.sql
-- Run as DBA after the operator has registered their own ACTIVE Identity.
-- Tạo/đăng ký login Operator, ánh xạ tới LivingDocs Identity
\set ON_ERROR_STOP on
BEGIN;
SELECT 1 / (CASE WHEN length(:'reason') BETWEEN 8 AND 500 THEN 1 ELSE 0 END) AS reason_required;
SELECT set_config('app.change_reason', :'reason', true);
SELECT 1 / (CASE WHEN EXISTS (
    SELECT 1 FROM identity.identities WHERE identity_id = :'operator_identity_id'::uuid
        AND status = 'ACTIVE'
) THEN 1 ELSE 0 END) AS active_identity_required;
SELECT 1 / (CASE WHEN :'operator_login' ~ '^ld_operator_[a-z0-9_]{3,40}$'
    THEN 1 ELSE 0 END) AS valid_login_required;
SELECT format('CREATE ROLE %I LOGIN NOBYPASSRLS', :'operator_login')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'operator_login') \gexec
ALTER ROLE :"operator_login" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT ld_s01_provisioner TO :"operator_login" WITH INHERIT TRUE, SET FALSE;
INSERT INTO identity.operator_principals(db_login_name, identity_id, status)
VALUES (:'operator_login', :'operator_identity_id'::uuid, 'ACTIVE')
ON CONFLICT (db_login_name) DO NOTHING;
SELECT 1 / (CASE WHEN EXISTS (
    SELECT 1 FROM identity.operator_principals
    WHERE db_login_name = :'operator_login' AND identity_id = :'operator_identity_id'::uuid
        AND status = 'ACTIVE'
) THEN 1 ELSE 0 END) AS mapping_required;
COMMIT;
-- Set a unique secret outside this file with \password <operator_login>.
