-- Disposable test database only; these credentials are not deployment secrets.
CREATE ROLE ld_s04_migrator LOGIN PASSWORD 'test-only';
CREATE ROLE ld_s04_app LOGIN PASSWORD 'test-only';
CREATE SCHEMA document AUTHORIZATION ld_s04_migrator;
GRANT USAGE ON SCHEMA document TO ld_s04_app;
