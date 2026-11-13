ALTER TABLE document.review_decisions ADD COLUMN response_snapshot JSONB;
ALTER TABLE document.review_decisions ADD CONSTRAINT ck_review_response_snapshot CHECK (response_snapshot IS NULL OR jsonb_typeof(response_snapshot) = 'object');

-- Preserve historical source context for keyed V2 decisions from their immutable audit.
UPDATE document.review_decisions d
SET response_snapshot = jsonb_build_object(
    'documentId', a.document_id, 'versionId', d.document_version_id,
    'state', d.resulting_state, 'reviewStage', d.resulting_stage,
    'revision', d.resulting_revision,
    'sourceBinding', CASE WHEN a.repository_connection_id IS NULL THEN NULL
      ELSE jsonb_build_object('repositoryConnectionId', a.repository_connection_id,
        'headSha', a.source_sha, 'targetKind', NULL, 'targetRef', NULL,
        'comparisonSha', NULL, 'configRevision', NULL, 'capturedAt', NULL) END)
FROM document.document_audits a
WHERE a.tenant_id = d.tenant_id AND a.document_version_id = d.document_version_id
  AND a.metadata->>'decisionId' = d.review_decision_id::text
  AND d.idempotency_key IS NOT NULL;
