-- LIV-138 PR2: Workspace authority to initiate a new S02 connection.
INSERT INTO identity.permission_catalog (permission_code) VALUES ('REPOSITORY_CONNECT');
INSERT INTO identity.role_permissions (role_code, permission_code) VALUES
    ('DEVELOPER', 'REPOSITORY_CONNECT'),
    ('TECHNICAL_LEAD', 'REPOSITORY_CONNECT');
