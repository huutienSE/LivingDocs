-- DBA-controlled one-shot bootstrap. Pass initial_identity and reason via psql
-- variables; execute from a named login with ld_s01_bootstrap membership.
-- Thực thi function V4 để cấp Platform Admin đầu tiên
\set ON_ERROR_STOP on
BEGIN;
SELECT identity.bootstrap_platform_admin(:'initial_identity'::uuid, :'reason') AS assignment_id;
COMMIT;
-- Revoke ld_s01_bootstrap membership from the named login immediately after use.
