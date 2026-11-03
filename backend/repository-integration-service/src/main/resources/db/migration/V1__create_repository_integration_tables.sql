-- LIV-114 | S02 - Repository Integration
-- Flyway V1: Repository, GitHub, Source and Export persistence
-- Schema owner: ld_s02_migrator
-- Runtime role: ld_s02_app

-- Protect Flyway metadata and future tables from runtime access.
REVOKE ALL PRIVILEGES
    ON TABLE repository.flyway_schema_history
    FROM ld_s02_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA repository
REVOKE ALL ON TABLES FROM ld_s02_app;


-- 1. GITHUB_CONNECTION
-- credential_ref stores a secret-manager reference, never
-- a raw GitHub access token.
CREATE TABLE repository.github_connections (
    github_connection_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    -- mã installation (github app cấp cho 1 tài khoản/tổ chức installation_id) của github, xác dịnh installation mà livingdocs sử dung, phân biệt các tenants
    external_installation_ref VARCHAR(255) NOT NULL,
    credential_ref VARCHAR(512) NOT NULL, -- tham chiếu đến nơi lưu chứng chỉ an toàn, secret/token dc mã hóa, dùng xác thực

    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    connected_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_github_connections_installation
       CHECK (BTRIM(external_installation_ref) <> ''),

    CONSTRAINT ck_github_connections_credential_ref
       CHECK (BTRIM(credential_ref) <> ''),

    CONSTRAINT ck_github_connections_status
       CHECK (BTRIM(status) <> ''),

    CONSTRAINT uq_github_connections_tenant_id
       UNIQUE (tenant_id, github_connection_id),

    CONSTRAINT uq_github_connections_installation
       UNIQUE (tenant_id, external_installation_ref)
);

-- ------------------------------------------------------------
-- 2. REPOSITORY_CONNECTION
-- workspace_id belongs to S01: logical reference only.
-- ------------------------------------------------------------

CREATE TABLE repository.repository_connections (
    repository_connection_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    workspace_id UUID NOT NULL,
    github_connection_id UUID NOT NULL,

    external_repo_id VARCHAR(100) NOT NULL,
    default_branch VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_repository_connections_external_repo
       CHECK (BTRIM(external_repo_id) <> ''),

    CONSTRAINT ck_repository_connections_status
       CHECK (BTRIM(status) <> ''),

    CONSTRAINT uq_repository_connections_tenant_id
       UNIQUE (tenant_id, repository_connection_id),

    CONSTRAINT uq_repository_connections_workspace_repo
       UNIQUE (tenant_id, workspace_id, external_repo_id),

    CONSTRAINT fk_repository_connections_github
       FOREIGN KEY (tenant_id, github_connection_id)
           REFERENCES repository.github_connections
               (tenant_id, github_connection_id)
           ON DELETE RESTRICT -- khong cho phep xoa ban ghi cha trong khi con ban ghi con dang tham chieu den
);


-- 3. TRACKED_BRANCH
CREATE TABLE repository.tracked_branches (
    tracked_branch_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    repository_connection_id UUID NOT NULL,
    ref_name VARCHAR(512) NOT NULL,

    active BOOLEAN NOT NULL DEFAULT TRUE,
    configured_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_tracked_branches_ref_name
     CHECK (BTRIM(ref_name) <> ''),

    CONSTRAINT uq_tracked_branches_tenant_id
     UNIQUE (tenant_id, tracked_branch_id),

    CONSTRAINT uq_tracked_branches_ref
     UNIQUE (tenant_id, repository_connection_id, ref_name),

    CONSTRAINT fk_tracked_branches_repository
     FOREIGN KEY (tenant_id, repository_connection_id)
         REFERENCES repository.repository_connections
             (tenant_id, repository_connection_id)
         ON DELETE RESTRICT
);


-- 4. TRIGGER_POLICY_REVISION
-- PLATFORM policy: tenant_id and scope_ref are NULL.
-- WORKSPACE/REPOSITORY policies are tenant-scoped.
-- Platform policy is readable but not writable by app role.

CREATE TABLE repository.trigger_policy_revisions (
    policy_revision_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    tenant_id UUID,
    scope_type VARCHAR(20) NOT NULL, -- repo/module/document
    scope_ref UUID,

    revision INTEGER NOT NULL, -- số phiên bản policy (1, 2, 3,..)
    rules JSONB NOT NULL, -- nội dung quy tắc kích hoạt, khi nào thì tự động update data

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_trigger_policy_scope_type
     CHECK (scope_type IN ('PLATFORM', 'WORKSPACE', 'REPOSITORY')),

    CONSTRAINT ck_trigger_policy_scope
     CHECK (
         (
             scope_type = 'PLATFORM'
                 AND tenant_id IS NULL
                 AND scope_ref IS NULL
             )
             OR
         (
             scope_type IN ('WORKSPACE', 'REPOSITORY')
                 AND tenant_id IS NOT NULL
                 AND scope_ref IS NOT NULL
             )
         ),

    CONSTRAINT ck_trigger_policy_revision
     CHECK (revision > 0),

    CONSTRAINT ck_trigger_policy_rules
     CHECK (jsonb_typeof(rules) = 'object')
);

CREATE UNIQUE INDEX uq_trigger_policy_platform_revision
    ON repository.trigger_policy_revisions (revision)
    WHERE scope_type = 'PLATFORM';

CREATE UNIQUE INDEX uq_trigger_policy_scoped_revision
    ON repository.trigger_policy_revisions
        (tenant_id, scope_type, scope_ref, revision)
    WHERE tenant_id IS NOT NULL;


-- 5. WEBHOOK_DELIVERY
-- provider_delivery_key supports webhook deduplication.
CREATE TABLE repository.webhook_deliveries (
    delivery_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    github_connection_id UUID NOT NULL,
    provider_delivery_key VARCHAR(255) NOT NULL,

    event_type VARCHAR(128) NOT NULL, -- push/PR
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    classification VARCHAR(64) NOT NULL, -- phân loại event khi tiếp nhận

    CONSTRAINT ck_webhook_delivery_key
       CHECK (BTRIM(provider_delivery_key) <> ''),

    CONSTRAINT ck_webhook_event_type
       CHECK (BTRIM(event_type) <> ''),

    CONSTRAINT ck_webhook_classification
       CHECK (BTRIM(classification) <> ''),

    CONSTRAINT uq_webhook_deliveries_tenant_id
       UNIQUE (tenant_id, delivery_id),

    CONSTRAINT uq_webhook_delivery_deduplication
       UNIQUE (tenant_id, github_connection_id, provider_delivery_key),

    CONSTRAINT fk_webhook_deliveries_github
       FOREIGN KEY (tenant_id, github_connection_id)
           REFERENCES repository.github_connections
               (tenant_id, github_connection_id)
           ON DELETE RESTRICT
);

-- 6. SOURCE_REVISION - phiên bản source code đã quan sát
CREATE TABLE repository.source_revisions (
    source_revision_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    repository_connection_id UUID NOT NULL,
    target_ref VARCHAR(512) NOT NULL,
    commit_sha VARCHAR(128) NOT NULL,

    observed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_source_revision_target_ref
     CHECK (BTRIM(target_ref) <> ''),

    CONSTRAINT ck_source_revision_commit_sha
     CHECK (BTRIM(commit_sha) <> ''),

    CONSTRAINT uq_source_revisions_tenant_id
     UNIQUE (tenant_id, source_revision_id),

    CONSTRAINT uq_source_revision_identity
     UNIQUE (
             tenant_id,
             repository_connection_id,
             target_ref,
             commit_sha
         ),

    CONSTRAINT fk_source_revisions_repository
     FOREIGN KEY (tenant_id, repository_connection_id)
         REFERENCES repository.repository_connections
             (tenant_id, repository_connection_id)
         ON DELETE RESTRICT
);

-- 7. SOURCE_VALIDATION_RECEIPT - biên nhận xác thực source code
CREATE TABLE repository.source_validation_receipts (
    validation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    repository_connection_id UUID NOT NULL,
    target_ref VARCHAR(512) NOT NULL,
    validated_head_sha VARCHAR(128) NOT NULL,
    config_revision VARCHAR(255) NOT NULL,

    validation_status VARCHAR(16) NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_source_validation_target_ref
       CHECK (BTRIM(target_ref) <> ''),

    CONSTRAINT ck_source_validation_sha
       CHECK (BTRIM(validated_head_sha) <> ''),

    CONSTRAINT ck_source_validation_config_revision
       CHECK (BTRIM(config_revision) <> ''),

    CONSTRAINT ck_source_validation_status
       CHECK (validation_status IN ('PASS', 'FAIL')),

    CONSTRAINT ck_source_validation_expiry
       CHECK (expires_at > checked_at),

    CONSTRAINT uq_source_validation_tenant_id
       UNIQUE (tenant_id, validation_id),

    CONSTRAINT fk_source_validation_repository
       FOREIGN KEY (tenant_id, repository_connection_id)
           REFERENCES repository.repository_connections
               (tenant_id, repository_connection_id)
           ON DELETE RESTRICT
);

-- 8. EXPORT_OPERATION
-- published_version_id belongs to S04: logical reference only.
-- Export status is a process status, not Document State.
CREATE TABLE repository.export_operations (
    export_operation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    repository_connection_id UUID NOT NULL,
    published_version_id UUID NOT NULL,

    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    target_ref VARCHAR(512) NOT NULL,

    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_export_operation_status
      CHECK (BTRIM(status) <> ''),

    CONSTRAINT ck_export_operation_target_ref
      CHECK (BTRIM(target_ref) <> ''),

    CONSTRAINT uq_export_operations_tenant_id
      UNIQUE (tenant_id, export_operation_id),

    CONSTRAINT fk_export_operations_repository
      FOREIGN KEY (tenant_id, repository_connection_id)
          REFERENCES repository.repository_connections
              (tenant_id, repository_connection_id)
          ON DELETE RESTRICT
);

-- ------------------------------------------------------------
-- 9. EXPORT_PROVENANCE
-- One export operation has at most one provenance record.
-- ------------------------------------------------------------

CREATE TABLE repository.export_provenance (
    provenance_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    export_operation_id UUID NOT NULL,

    branch_ref VARCHAR(512) NOT NULL,
    pull_request_ref VARCHAR(255),
    export_commit_sha VARCHAR(128) NOT NULL,

    exported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_export_provenance_branch
      CHECK (BTRIM(branch_ref) <> ''),

    CONSTRAINT ck_export_provenance_commit_sha
      CHECK (BTRIM(export_commit_sha) <> ''),

    CONSTRAINT uq_export_provenance_tenant_id
      UNIQUE (tenant_id, provenance_id),

    CONSTRAINT uq_export_provenance_operation
      UNIQUE (tenant_id, export_operation_id),

    CONSTRAINT fk_export_provenance_operation
      FOREIGN KEY (tenant_id, export_operation_id)
          REFERENCES repository.export_operations
              (tenant_id, export_operation_id)
          ON DELETE RESTRICT
);

-- ------------------------------------------------------------
-- 10. INDEXES
-- ------------------------------------------------------------

CREATE INDEX idx_repository_connections_tenant_workspace
    ON repository.repository_connections (tenant_id, workspace_id);

CREATE INDEX idx_tracked_branches_active
    ON repository.tracked_branches
        (tenant_id, repository_connection_id, active);

CREATE INDEX idx_webhook_deliveries_received
    ON repository.webhook_deliveries
        (tenant_id, received_at DESC);

CREATE INDEX idx_source_revisions_observed
    ON repository.source_revisions
        (tenant_id, repository_connection_id, observed_at DESC);

CREATE INDEX idx_source_validation_checked
    ON repository.source_validation_receipts
        (tenant_id, repository_connection_id, checked_at DESC);

CREATE INDEX idx_export_operations_status
    ON repository.export_operations
        (tenant_id, status, requested_at DESC);

CREATE INDEX idx_trigger_policy_scoped_lookup
    ON repository.trigger_policy_revisions
        (tenant_id, scope_type, scope_ref, revision DESC)
    WHERE tenant_id IS NOT NULL;

-- ------------------------------------------------------------
-- 11. ROW-LEVEL SECURITY
-- ------------------------------------------------------------

ALTER TABLE repository.github_connections
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE repository.repository_connections
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE repository.tracked_branches
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE repository.webhook_deliveries
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE repository.source_revisions
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE repository.source_validation_receipts
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE repository.export_operations
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE repository.export_provenance
    ENABLE ROW LEVEL SECURITY;

CREATE POLICY github_connections_tenant_isolation
ON repository.github_connections
FOR ALL TO ld_s02_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY repository_connections_tenant_isolation
ON repository.repository_connections
FOR ALL TO ld_s02_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY tracked_branches_tenant_isolation
ON repository.tracked_branches
FOR ALL TO ld_s02_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY webhook_deliveries_tenant_isolation
ON repository.webhook_deliveries
FOR ALL TO ld_s02_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY source_revisions_tenant_isolation
ON repository.source_revisions
FOR ALL TO ld_s02_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY source_validation_tenant_isolation
ON repository.source_validation_receipts
FOR ALL TO ld_s02_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY export_operations_tenant_isolation
ON repository.export_operations
FOR ALL TO ld_s02_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY export_provenance_tenant_isolation
ON repository.export_provenance
FOR ALL TO ld_s02_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

-- Platform trigger policies are readable by tenant apps,
-- but app role cannot create/update/delete platform policies.
ALTER TABLE repository.trigger_policy_revisions
    ENABLE ROW LEVEL SECURITY;

CREATE POLICY trigger_policy_read
ON repository.trigger_policy_revisions
FOR SELECT TO ld_s02_app
               USING (
               tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
               OR tenant_id IS NULL
               );

CREATE POLICY trigger_policy_insert
ON repository.trigger_policy_revisions
FOR INSERT TO ld_s02_app
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY trigger_policy_update
ON repository.trigger_policy_revisions
FOR UPDATE TO ld_s02_app
                      USING (
                      tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
                      )
    WITH CHECK (
                      tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
                      );

CREATE POLICY trigger_policy_delete
ON repository.trigger_policy_revisions
FOR DELETE TO ld_s02_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

-- ------------------------------------------------------------
-- 12. RUNTIME PRIVILEGES
-- Do not grant privileges on flyway_schema_history.
-- ------------------------------------------------------------

GRANT USAGE ON SCHEMA repository TO ld_s02_app;

GRANT SELECT, INSERT, UPDATE, DELETE
      ON repository.github_connections,
          repository.repository_connections,
          repository.tracked_branches,
          repository.trigger_policy_revisions,
          repository.webhook_deliveries,
          repository.source_revisions,
          repository.source_validation_receipts,
          repository.export_operations,
          repository.export_provenance
TO ld_s02_app;