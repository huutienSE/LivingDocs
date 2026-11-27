-- LIV-162: run as DBA before S01 Flyway V7 on an existing database.
-- The app login may select this narrow role only inside an HTTP mutation transaction.
-- Role ld_s01_http_writer ghi giới hạn Được kích hoạt có kiểm soát trong transaction để ghi Grant.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ld_s01_http_writer') THEN
        CREATE ROLE ld_s01_http_writer NOLOGIN NOBYPASSRLS;
    END IF;
END $$;
ALTER ROLE ld_s01_http_writer NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT ld_s01_http_writer TO ld_s01_app WITH INHERIT FALSE, SET TRUE, ADMIN FALSE;
