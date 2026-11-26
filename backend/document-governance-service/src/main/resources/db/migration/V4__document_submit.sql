-- Additive LIV-143 migration. Do not edit V1-V3 or infer missing legacy provenance.
ALTER TABLE document.document_versions ADD COLUMN source_target_kind VARCHAR(32), ADD COLUMN source_target_ref VARCHAR(255), ADD COLUMN source_comparison_sha VARCHAR(128), ADD COLUMN source_config_revision VARCHAR(255), ADD COLUMN source_captured_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_submit_target_kind CHECK (source_target_kind IS NULL OR source_target_kind IN ('PR','TRACKED_BRANCH'));
CREATE TABLE document.submit_requests (
    submission_id UUID PRIMARY KEY, tenant_id UUID NOT NULL, document_version_id UUID NOT NULL, actor_identity_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    expected_revision BIGINT NOT NULL CHECK (expected_revision >= 0), resulting_revision BIGINT NOT NULL CHECK (resulting_revision = expected_revision + 1),
    note VARCHAR(2000), response_snapshot JSONB NOT NULL CHECK (jsonb_typeof(response_snapshot) = 'object'), submitted_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (tenant_id,document_version_id) REFERENCES document.document_versions(tenant_id,document_version_id) ON DELETE RESTRICT,
    UNIQUE (tenant_id,document_version_id,actor_identity_id,idempotency_key), UNIQUE (tenant_id,document_version_id,resulting_revision)
);
ALTER TABLE document.submit_requests ENABLE ROW LEVEL SECURITY;
CREATE POLICY submit_requests_tenant_isolation ON document.submit_requests FOR ALL TO ld_s04_app
USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::UUID) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::UUID);
GRANT SELECT, INSERT ON document.submit_requests TO ld_s04_app;
