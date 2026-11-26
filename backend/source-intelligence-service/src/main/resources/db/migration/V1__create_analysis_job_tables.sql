-- LIV-125 | S03 - Source Intelligence (Job Foundation)
-- Flyway V1: AnalysisJob identity, immutable snapshot binding, attempt/lease and outcome disposition
-- Schema owner: ld_s03_migrator
-- Runtime role: ld_s03_app

-- Protect Flyway metadata and future tables from runtime access.
REVOKE ALL PRIVILEGES
    ON TABLE intelligence.flyway_schema_history
    FROM ld_s03_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA intelligence
REVOKE ALL ON TABLES FROM ld_s03_app;

CREATE TABLE intelligence.analysis_jobs (
    job_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    workspace_id UUID NOT NULL,
    repository_connection_id UUID NOT NULL,
    external_job_key VARCHAR(160) NOT NULL,

    target_kind VARCHAR(32) NOT NULL,
    target_ref VARCHAR(512) NOT NULL,
    comparison_intent VARCHAR(32) NOT NULL,
    comparison_before_sha VARCHAR(128),
    authoritative_target_sha VARCHAR(128) NOT NULL,
    scope_manifest_id VARCHAR(128),
    config_revision VARCHAR(255),
    declared_scope_completeness VARCHAR(32) NOT NULL,
    whitespace_no_impact_enabled BOOLEAN NOT NULL DEFAULT FALSE,

    status VARCHAR(32) NOT NULL DEFAULT 'CLAIMED',
    current_attempt_id VARCHAR(128) NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    result_disposition VARCHAR(48) NOT NULL DEFAULT 'PENDING',

    processing_status VARCHAR(32),
    overall_outcome VARCHAR(32),
    evidence_status VARCHAR(32),
    coverage_summary JSONB NOT NULL DEFAULT '{}'::JSONB,
    safe_diagnostics JSONB NOT NULL DEFAULT '[]'::JSONB,

    applied_attempt_id VARCHAR(128),
    applied_source_revision BIGINT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ck_analysis_jobs_external_key
        CHECK (BTRIM(external_job_key) <> ''),

    CONSTRAINT ck_analysis_jobs_target_kind
        CHECK (target_kind IN ('PULL_REQUEST', 'TRACKED_BRANCH')),

    CONSTRAINT ck_analysis_jobs_target_ref
        CHECK (BTRIM(target_ref) <> ''),

    CONSTRAINT ck_analysis_jobs_intent
        CHECK (comparison_intent IN ('REVISION_COMPARISON', 'INITIAL_ASSESSMENT')),

    CONSTRAINT ck_analysis_jobs_before_sha
        CHECK (
            (comparison_intent = 'REVISION_COMPARISON'
                AND comparison_before_sha IS NOT NULL
                AND BTRIM(comparison_before_sha) <> '')
            OR
            (comparison_intent = 'INITIAL_ASSESSMENT'
                AND comparison_before_sha IS NULL)
        ),

    CONSTRAINT ck_analysis_jobs_target_sha
        CHECK (BTRIM(authoritative_target_sha) <> ''),

    CONSTRAINT ck_analysis_jobs_scope_manifest
        CHECK (scope_manifest_id IS NULL OR BTRIM(scope_manifest_id) <> ''),

    CONSTRAINT ck_analysis_jobs_config_revision
        CHECK (config_revision IS NULL OR BTRIM(config_revision) <> ''),

    CONSTRAINT ck_analysis_jobs_scope_completeness
        CHECK (declared_scope_completeness IN (
            'COMPLETE', 'PARTIAL', 'EMPTY_VERIFIED', 'EMPTY_UNVERIFIED', 'DENIED'
        )),

    CONSTRAINT ck_analysis_jobs_status
        CHECK (status IN ('CLAIMED', 'COMPLETED', 'SUPPRESSED', 'FAILED', 'RETRY_PENDING')),

    CONSTRAINT ck_analysis_jobs_attempt_id
        CHECK (BTRIM(current_attempt_id) <> ''),

    CONSTRAINT ck_analysis_jobs_disposition
        CHECK (result_disposition IN (
            'PENDING', 'APPLIED_CURRENT', 'SUPPRESSED_STALE',
            'DEFERRED_UNKNOWN_CURRENTNESS', 'TERMINAL_FAILED'
        )),

    CONSTRAINT ck_analysis_jobs_processing_status
        CHECK (processing_status IS NULL OR processing_status IN (
            'COMPLETE', 'PARTIAL', 'FAILED', 'OUT_OF_SCOPE'
        )),

    CONSTRAINT ck_analysis_jobs_overall_outcome
        CHECK (overall_outcome IS NULL OR overall_outcome IN (
            'ACTIONABLE', 'NO_IMPACT', 'INCONCLUSIVE'
        )),

    CONSTRAINT ck_analysis_jobs_evidence_status
        CHECK (evidence_status IS NULL OR evidence_status IN (
            'VERIFIED', 'PARTIAL', 'REJECTED'
        )),

    CONSTRAINT ck_analysis_jobs_coverage_json
        CHECK (jsonb_typeof(coverage_summary) = 'object'),

    CONSTRAINT ck_analysis_jobs_diagnostics_json
        CHECK (jsonb_typeof(safe_diagnostics) = 'array'),

    CONSTRAINT uq_analysis_jobs_tenant_id
        UNIQUE (tenant_id, job_id),

    CONSTRAINT uq_analysis_jobs_external_key
        UNIQUE (tenant_id, external_job_key)
);

CREATE INDEX idx_analysis_jobs_repo_created
    ON intelligence.analysis_jobs (tenant_id, repository_connection_id, created_at DESC);

CREATE INDEX idx_analysis_jobs_target_created
    ON intelligence.analysis_jobs (tenant_id, repository_connection_id, target_kind, target_ref, created_at DESC);

ALTER TABLE intelligence.analysis_jobs
    ENABLE ROW LEVEL SECURITY;

CREATE POLICY analysis_jobs_tenant_isolation
ON intelligence.analysis_jobs
FOR ALL TO ld_s03_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

GRANT USAGE ON SCHEMA intelligence TO ld_s03_app;

GRANT SELECT, INSERT, UPDATE
    ON intelligence.analysis_jobs
TO ld_s03_app;
