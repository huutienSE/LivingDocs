-- LIV-118: preserve V1/history; runtime retains append-only decision/audit grants.
ALTER TABLE document.document_versions
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0);

-- PostgreSQL CHECK accepts UNKNOWN: explicitly reject NULL stage for IN_REVIEW.
ALTER TABLE document.document_versions DROP CONSTRAINT ck_document_versions_review_stage;
ALTER TABLE document.document_versions ADD CONSTRAINT ck_document_versions_review_stage CHECK (
    (state = 'IN_REVIEW' AND review_stage IS NOT NULL
        AND review_stage IN ('STAFF_REVIEW', 'MANAGER_APPROVAL'))
    OR (state <> 'IN_REVIEW' AND review_stage IS NULL)
);

ALTER TABLE document.review_decisions
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD COLUMN expected_revision BIGINT,
    ADD COLUMN resulting_revision BIGINT,
    ADD COLUMN resulting_state VARCHAR(32),
    ADD COLUMN resulting_stage VARCHAR(32),
    ADD COLUMN evidence_refs JSONB NOT NULL DEFAULT '[]'::JSONB,
    ADD CONSTRAINT ck_review_decisions_evidence CHECK (jsonb_typeof(evidence_refs) = 'array'),
    ADD CONSTRAINT ck_review_decisions_result CHECK (
        (idempotency_key IS NULL AND expected_revision IS NULL AND resulting_revision IS NULL
            AND resulting_state IS NULL AND resulting_stage IS NULL)
        OR (idempotency_key IS NOT NULL AND BTRIM(idempotency_key) <> ''
            AND expected_revision IS NOT NULL AND expected_revision >= 0
            AND resulting_revision IS NOT NULL AND resulting_revision = expected_revision + 1
            AND resulting_state IS NOT NULL
            AND ((decision = 'PASS' AND resulting_state = 'IN_REVIEW'
                    AND resulting_stage IS NOT NULL AND resulting_stage = 'MANAGER_APPROVAL')
                OR (decision = 'RETURN_FOR_REVISION' AND resulting_state = 'DRAFT' AND resulting_stage IS NULL)))
    ),
    ADD CONSTRAINT uq_review_decisions_request
        UNIQUE (tenant_id, document_version_id, reviewer_identity_id, idempotency_key),
    ADD CONSTRAINT uq_review_decisions_revision
        UNIQUE (tenant_id, document_version_id, resulting_revision);
