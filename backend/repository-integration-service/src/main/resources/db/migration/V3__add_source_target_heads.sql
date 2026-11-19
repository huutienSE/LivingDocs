CREATE TABLE repository.source_target_heads (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    repository_connection_id UUID NOT NULL,
    target_kind VARCHAR(16) NOT NULL,
    target_ref VARCHAR(512) NOT NULL,
    authority_repository_id VARCHAR(19) NOT NULL,
    head_state VARCHAR(7) NOT NULL,
    source_repository_id VARCHAR(19),
    head_sha VARCHAR(40),
    observation_version BIGINT NOT NULL,
    source_revision BIGINT NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),

    CONSTRAINT pk_source_target_heads PRIMARY KEY
        (tenant_id, workspace_id, repository_connection_id, target_kind, target_ref),
    CONSTRAINT fk_source_target_heads_connection FOREIGN KEY (tenant_id, repository_connection_id)
        REFERENCES repository.repository_connections (tenant_id, repository_connection_id) ON DELETE RESTRICT,
    CONSTRAINT ck_source_target_heads_target CHECK (
        (target_kind = 'BRANCH' AND target_ref LIKE 'refs/heads/_%') OR
        (target_kind = 'PR' AND target_ref ~ '^[1-9][0-9]{0,9}$'
            AND (length(target_ref) < 10 OR target_ref COLLATE "C" <= '2147483647'))
    ),
    CONSTRAINT ck_source_target_heads_authority CHECK (authority_repository_id ~ '^[1-9][0-9]{0,18}$'),
    CONSTRAINT ck_source_target_heads_versions CHECK (
        source_revision > 0 AND observation_version >= source_revision
    ),
    CONSTRAINT ck_source_target_heads_state CHECK (
        (head_state = 'PRESENT'
            AND source_repository_id IS NOT NULL AND source_repository_id ~ '^[1-9][0-9]{0,18}$'
            AND head_sha IS NOT NULL AND head_sha ~ '^[0-9a-f]{40}$' AND head_sha <> repeat('0', 40))
        OR (head_state = 'ABSENT' AND source_repository_id IS NULL AND head_sha IS NULL)
    )
);

ALTER TABLE repository.source_target_heads ENABLE ROW LEVEL SECURITY;
ALTER TABLE repository.source_target_heads FORCE ROW LEVEL SECURITY;
CREATE POLICY source_target_heads_tenant_isolation ON repository.source_target_heads
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

GRANT SELECT, INSERT ON repository.source_target_heads TO ld_s02_app;
GRANT UPDATE (head_state, source_repository_id, head_sha, observation_version, source_revision, observed_at)
    ON repository.source_target_heads TO ld_s02_app;
