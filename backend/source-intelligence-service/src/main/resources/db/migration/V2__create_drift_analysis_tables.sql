-- LIV-127 | S03 - Source Intelligence (M5 Persistence & Drift Lifecycle)
-- Flyway V2: Target fences, DriftIssues, AnalysisFindings (versioned JSONB), and append-only DriftEvidence observations
-- Schema owner: ld_s03_migrator
-- Runtime role: ld_s03_app

-- 1. TARGET_ANALYSIS_FENCES
-- Monotonic per-target currentness fence to suppress out-of-order stale job effects.
CREATE TABLE intelligence.target_analysis_fences (
    fence_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    repository_connection_id UUID NOT NULL,
    target_kind VARCHAR(32) NOT NULL,
    target_ref VARCHAR(512) NOT NULL,

    latest_source_revision BIGINT NOT NULL,
    latest_validated_sha VARCHAR(128) NOT NULL,
    latest_config_revision VARCHAR(255) NOT NULL,
    last_fenced_job_id UUID,
    last_validated_at TIMESTAMPTZ NOT NULL,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ck_target_fences_target_kind
        CHECK (target_kind IN ('PULL_REQUEST', 'TRACKED_BRANCH')),

    CONSTRAINT ck_target_fences_target_ref
        CHECK (BTRIM(target_ref) <> ''),

    CONSTRAINT ck_target_fences_revision
        CHECK (latest_source_revision >= 0),

    CONSTRAINT ck_target_fences_sha
        CHECK (BTRIM(latest_validated_sha) <> ''),

    CONSTRAINT ck_target_fences_config_revision
        CHECK (BTRIM(latest_config_revision) <> ''),

    CONSTRAINT uq_target_fences_tenant_id
        UNIQUE (tenant_id, fence_id),

    CONSTRAINT uq_target_fences_scope
        UNIQUE (tenant_id, repository_connection_id, target_kind, target_ref),

    CONSTRAINT fk_target_fences_last_job
        FOREIGN KEY (tenant_id, last_fenced_job_id)
            REFERENCES intelligence.analysis_jobs (tenant_id, job_id)
            ON DELETE RESTRICT
);

-- 2. DRIFT_ISSUES
-- Business drift lifecycle per REQ-05. Only one active/open issue per business identity per target.
CREATE TABLE intelligence.drift_issues (
    drift_issue_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    workspace_id UUID NOT NULL,
    repository_connection_id UUID NOT NULL,
    target_kind VARCHAR(32) NOT NULL,
    target_ref VARCHAR(512) NOT NULL,

    document_id UUID NOT NULL,
    document_version VARCHAR(128) NOT NULL,
    binding_id VARCHAR(128) NOT NULL,
    claim_id VARCHAR(128) NOT NULL,
    canonical_subject_key TEXT NOT NULL,
    issue_identity_key VARCHAR(64) NOT NULL,

    drift_classification VARCHAR(32),
    severity_status VARCHAR(32) NOT NULL,
    severity VARCHAR(32),
    state VARCHAR(32) NOT NULL,

    opened_at_sha VARCHAR(128) NOT NULL,
    last_observed_sha VARCHAR(128) NOT NULL,
    last_observed_at TIMESTAMPTZ NOT NULL,
    last_consistent_sha VARCHAR(128),
    last_consistent_at TIMESTAMPTZ,
    revert_observed BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ck_drift_issues_target_kind
        CHECK (target_kind IN ('PULL_REQUEST', 'TRACKED_BRANCH')),

    CONSTRAINT ck_drift_issues_target_ref
        CHECK (BTRIM(target_ref) <> ''),

    CONSTRAINT ck_drift_issues_doc_version
        CHECK (BTRIM(document_version) <> ''),

    CONSTRAINT ck_drift_issues_binding_id
        CHECK (BTRIM(binding_id) <> ''),

    CONSTRAINT ck_drift_issues_claim_id
        CHECK (BTRIM(claim_id) <> ''),

    CONSTRAINT ck_drift_issues_subject_key
        CHECK (BTRIM(canonical_subject_key) <> ''),

    CONSTRAINT ck_drift_issues_identity_key
        CHECK (BTRIM(issue_identity_key) <> ''),

    CONSTRAINT ck_drift_issues_classification
        CHECK (drift_classification IS NULL OR drift_classification IN (
            'REFERENTIAL', 'SIGNATURE', 'SEMANTIC'
        )),

    CONSTRAINT ck_drift_issues_severity_status
        CHECK (severity_status IN ('EVALUATED', 'SEVERITY_NOT_EVALUATED')),

    CONSTRAINT ck_drift_issues_severity
        CHECK (
            (severity_status = 'EVALUATED' AND severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
            OR
            (severity_status = 'SEVERITY_NOT_EVALUATED' AND severity IS NULL)
        ),

    CONSTRAINT ck_drift_issues_state
        CHECK (state IN ('DETECTED', 'ANALYZED', 'SUGGESTED', 'RESOLVED', 'DISMISSED')),

    CONSTRAINT ck_drift_issues_opened_sha
        CHECK (BTRIM(opened_at_sha) <> ''),

    CONSTRAINT ck_drift_issues_last_observed_sha
        CHECK (BTRIM(last_observed_sha) <> ''),

    CONSTRAINT ck_drift_issues_last_consistent
        CHECK (
            (last_consistent_sha IS NULL AND last_consistent_at IS NULL)
            OR
            (last_consistent_sha IS NOT NULL AND BTRIM(last_consistent_sha) <> '' AND last_consistent_at IS NOT NULL)
        ),

    CONSTRAINT uq_drift_issues_tenant_id
        UNIQUE (tenant_id, drift_issue_id)
);

-- Closed issues (RESOLVED, DISMISSED) leave this partial unique index and remain immutable history.
CREATE UNIQUE INDEX uq_drift_issues_open_identity
    ON intelligence.drift_issues (
        tenant_id,
        repository_connection_id,
        target_kind,
        target_ref,
        issue_identity_key
    )
    WHERE state IN ('DETECTED', 'ANALYZED', 'SUGGESTED');

CREATE INDEX idx_drift_issues_repo_observed
    ON intelligence.drift_issues (tenant_id, repository_connection_id, last_observed_at DESC, drift_issue_id);

-- 3. ANALYSIS_FINDINGS
-- Selective persistence per GAP-18: stores NO_IMPACT / INCONCLUSIVE / EXCLUDED / ACTIONABLE with versioned JSONB once.
CREATE TABLE intelligence.analysis_findings (
    finding_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    job_id UUID NOT NULL,
    drift_issue_id UUID,

    deterministic_finding_key VARCHAR(80) NOT NULL,
    unit_disposition VARCHAR(32) NOT NULL,
    impact_outcome VARCHAR(32) NOT NULL,
    drift_classification VARCHAR(32),
    severity_status VARCHAR(32) NOT NULL,
    severity VARCHAR(32),
    semantic_confidence DOUBLE PRECISION,
    issue_admission VARCHAR(48) NOT NULL,
    primary_reason VARCHAR(96) NOT NULL,
    admission_reason VARCHAR(96) NOT NULL,
    evidence_status VARCHAR(32) NOT NULL,

    evidence_schema_version VARCHAR(32) NOT NULL,
    evidence_payload JSONB NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ck_analysis_findings_det_key
        CHECK (BTRIM(deterministic_finding_key) <> ''),

    CONSTRAINT ck_analysis_findings_disposition
        CHECK (unit_disposition IN ('EVALUATED', 'EXCLUDED')),

    CONSTRAINT ck_analysis_findings_outcome
        CHECK (impact_outcome IN ('ACTIONABLE', 'NO_IMPACT', 'INCONCLUSIVE')),

    CONSTRAINT ck_analysis_findings_classification
        CHECK (drift_classification IS NULL OR drift_classification IN (
            'REFERENTIAL', 'SIGNATURE', 'SEMANTIC'
        )),

    CONSTRAINT ck_analysis_findings_severity_status
        CHECK (severity_status IN ('EVALUATED', 'SEVERITY_NOT_EVALUATED')),

    CONSTRAINT ck_analysis_findings_severity
        CHECK (
            (severity_status = 'EVALUATED' AND severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
            OR
            (severity_status = 'SEVERITY_NOT_EVALUATED' AND severity IS NULL)
        ),

    CONSTRAINT ck_analysis_findings_confidence
        CHECK (semantic_confidence IS NULL OR (semantic_confidence >= 0.0 AND semantic_confidence <= 1.0)),

    CONSTRAINT ck_analysis_findings_admission
        CHECK (issue_admission IN (
            'SUPPORTED_BY_INITIAL_EVIDENCE', 'INSUFFICIENT_EVIDENCE', 'NOT_APPLICABLE'
        )),

    CONSTRAINT ck_analysis_findings_primary_reason
        CHECK (BTRIM(primary_reason) <> ''),

    CONSTRAINT ck_analysis_findings_admission_reason
        CHECK (BTRIM(admission_reason) <> ''),

    CONSTRAINT ck_analysis_findings_evidence_status
        CHECK (evidence_status IN ('VERIFIED', 'PARTIAL', 'REJECTED')),

    CONSTRAINT ck_analysis_findings_schema_ver
        CHECK (BTRIM(evidence_schema_version) <> ''),

    CONSTRAINT ck_analysis_findings_payload_json
        CHECK (jsonb_typeof(evidence_payload) = 'object'),

    CONSTRAINT uq_analysis_findings_tenant_id
        UNIQUE (tenant_id, finding_id),

    CONSTRAINT uq_analysis_findings_job_key
        UNIQUE (tenant_id, job_id, deterministic_finding_key),

    CONSTRAINT uq_analysis_findings_issue_pair
        UNIQUE (tenant_id, finding_id, drift_issue_id),

    CONSTRAINT fk_analysis_findings_job
        FOREIGN KEY (tenant_id, job_id)
            REFERENCES intelligence.analysis_jobs (tenant_id, job_id)
            ON DELETE RESTRICT,

    CONSTRAINT fk_analysis_findings_issue
        FOREIGN KEY (tenant_id, drift_issue_id)
            REFERENCES intelligence.drift_issues (tenant_id, drift_issue_id)
            ON DELETE RESTRICT
);

CREATE INDEX idx_analysis_findings_job
    ON intelligence.analysis_findings (tenant_id, job_id);

CREATE INDEX idx_analysis_findings_issue
    ON intelligence.analysis_findings (tenant_id, drift_issue_id)
    WHERE drift_issue_id IS NOT NULL;

-- 4. DRIFT_EVIDENCE
-- Append-only observation links tying a DriftIssue to its supporting or revert-confirming AnalysisFinding.
CREATE TABLE intelligence.drift_evidence (
    drift_evidence_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    drift_issue_id UUID NOT NULL,
    finding_id UUID NOT NULL,

    observation_kind VARCHAR(32) NOT NULL,
    evaluated_target_sha VARCHAR(128) NOT NULL,
    document_version VARCHAR(128) NOT NULL,
    document_bound_sha VARCHAR(128) NOT NULL,
    content_fingerprint VARCHAR(64) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_drift_evidence_obs_kind
        CHECK (observation_kind IN (
            'MISMATCH_DETECTED', 'MISMATCH_ANALYZED', 'CONSISTENCY_REVERTED'
        )),

    CONSTRAINT ck_drift_evidence_target_sha
        CHECK (BTRIM(evaluated_target_sha) <> ''),

    CONSTRAINT ck_drift_evidence_doc_version
        CHECK (BTRIM(document_version) <> ''),

    CONSTRAINT ck_drift_evidence_doc_bound_sha
        CHECK (BTRIM(document_bound_sha) <> ''),

    CONSTRAINT ck_drift_evidence_fingerprint
        CHECK (BTRIM(content_fingerprint) <> ''),

    CONSTRAINT uq_drift_evidence_tenant_id
        UNIQUE (tenant_id, drift_evidence_id),

    CONSTRAINT uq_drift_evidence_observation
        UNIQUE (tenant_id, drift_issue_id, finding_id, observation_kind),

    CONSTRAINT fk_drift_evidence_issue
        FOREIGN KEY (tenant_id, drift_issue_id)
            REFERENCES intelligence.drift_issues (tenant_id, drift_issue_id)
            ON DELETE RESTRICT,

    CONSTRAINT fk_drift_evidence_finding_issue
        FOREIGN KEY (tenant_id, finding_id, drift_issue_id)
            REFERENCES intelligence.analysis_findings (tenant_id, finding_id, drift_issue_id)
            ON DELETE RESTRICT
);

CREATE INDEX idx_drift_evidence_issue_observed
    ON intelligence.drift_evidence (tenant_id, drift_issue_id, observed_at DESC, drift_evidence_id);

-- 5. ROW-LEVEL SECURITY & RUNTIME PRIVILEGES
ALTER TABLE intelligence.target_analysis_fences
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE intelligence.drift_issues
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE intelligence.analysis_findings
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE intelligence.drift_evidence
    ENABLE ROW LEVEL SECURITY;

CREATE POLICY target_analysis_fences_tenant_isolation
ON intelligence.target_analysis_fences
FOR ALL TO ld_s03_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY drift_issues_tenant_isolation
ON intelligence.drift_issues
FOR ALL TO ld_s03_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY analysis_findings_tenant_isolation
ON intelligence.analysis_findings
FOR ALL TO ld_s03_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY drift_evidence_tenant_isolation
ON intelligence.drift_evidence
FOR ALL TO ld_s03_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

GRANT SELECT, INSERT, UPDATE
    ON intelligence.target_analysis_fences,
       intelligence.drift_issues,
       intelligence.analysis_findings
TO ld_s03_app;

-- Observation evidence is strictly append-only for the runtime role.
GRANT SELECT, INSERT
    ON intelligence.drift_evidence
TO ld_s03_app;
