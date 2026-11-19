-- Chỉ nạp vào PostgreSQL phát triển cục bộ. Đây là dữ liệu thử, không phải Flyway migration.
-- psql yêu cầu -v installation_id=... -v repository_id=... -v credential_ref=ten-tep.pem
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL ROLE ld_s02_app;
SELECT set_config('app.tenant_id', '12200000-0000-4000-8000-000000000001', true);

INSERT INTO repository.github_connections
    (github_connection_id, tenant_id, external_installation_ref, credential_ref, status, connected_at)
VALUES
    ('12200000-0000-4000-8000-000000000003', '12200000-0000-4000-8000-000000000001',
     :'installation_id', :'credential_ref', 'CONNECTED', CURRENT_TIMESTAMP)
ON CONFLICT (github_connection_id) DO NOTHING;

INSERT INTO repository.repository_connections
    (repository_connection_id, tenant_id, workspace_id, github_connection_id, external_repo_id, default_branch, status)
VALUES
    ('12200000-0000-4000-8000-000000000004', '12200000-0000-4000-8000-000000000001',
     '12200000-0000-4000-8000-000000000002', '12200000-0000-4000-8000-000000000003',
     :'repository_id', 'main', 'ACTIVE')
ON CONFLICT (repository_connection_id) DO NOTHING;

INSERT INTO repository.tracked_branches
    (tracked_branch_id, tenant_id, repository_connection_id, ref_name, active)
VALUES
    ('12200000-0000-4000-8000-000000000005', '12200000-0000-4000-8000-000000000001',
     '12200000-0000-4000-8000-000000000004', 'refs/heads/main', true)
ON CONFLICT (tracked_branch_id) DO NOTHING;

-- Không ghi đè khi fixture cũ đã bị đổi. Thoát trước COMMIT để hoàn tác nếu binding sai.
SELECT EXISTS (
    SELECT 1 FROM repository.repository_connections r
    JOIN repository.github_connections g
      ON g.tenant_id = r.tenant_id AND g.github_connection_id = r.github_connection_id
    JOIN repository.tracked_branches b
      ON b.tenant_id = r.tenant_id AND b.repository_connection_id = r.repository_connection_id
    WHERE r.tenant_id = '12200000-0000-4000-8000-000000000001'
      AND r.workspace_id = '12200000-0000-4000-8000-000000000002'
      AND r.repository_connection_id = '12200000-0000-4000-8000-000000000004'
      AND g.github_connection_id = '12200000-0000-4000-8000-000000000003'
      AND g.external_installation_ref = :'installation_id' AND g.credential_ref = :'credential_ref'
      AND g.status = 'CONNECTED' AND r.external_repo_id = :'repository_id' AND r.status = 'ACTIVE'
      AND r.default_branch = 'main' AND b.ref_name = 'refs/heads/main' AND b.active
) AS fixture_matches \gset
\if :fixture_matches
COMMIT;
\else
ROLLBACK;
\echo 'Fixture LIV-122 da ton tai voi cau hinh khac. Kiem tra du lieu truoc khi chay lai.'
\quit 1
\endif
