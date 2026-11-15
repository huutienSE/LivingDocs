CREATE ROLE ld_s01_app LOGIN PASSWORD 'test_app_password';
CREATE SCHEMA identity AUTHORIZATION postgres;
GRANT USAGE ON SCHEMA identity TO ld_s01_app;
