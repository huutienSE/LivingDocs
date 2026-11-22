-- Run as DBA immediately after successful one-shot bootstrap.
-- 	Vô hiệu hóa quyền/login bootstrap sau khi dùng
\set ON_ERROR_STOP on
BEGIN;
REVOKE ld_s01_bootstrap FROM :"bootstrap_login";
ALTER ROLE :"bootstrap_login" NOLOGIN;
COMMIT;
