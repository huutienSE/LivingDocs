-- Run as DBA with -v operator_login=ld_operator_<name> -v reason=...
-- Vô hiệu hóa quyền vận hành của Operator
\set ON_ERROR_STOP on
BEGIN;
SELECT 1 / (CASE WHEN length(:'reason') BETWEEN 8 AND 500 THEN 1 ELSE 0 END) AS reason_required;
SELECT set_config('app.change_reason', :'reason', true);
UPDATE identity.operator_principals
SET status='REVOKED', revoked_at=CURRENT_TIMESTAMP
WHERE db_login_name=:'operator_login' AND status='ACTIVE';
REVOKE ld_s01_provisioner FROM :"operator_login";
ALTER ROLE :"operator_login" NOLOGIN;
COMMIT;
