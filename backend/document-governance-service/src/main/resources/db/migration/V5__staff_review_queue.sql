-- LIV-144: additive read model; do not infer severity for historical candidates.
ALTER TABLE document.document_versions ADD COLUMN drift_severity VARCHAR(16),
    ADD CONSTRAINT ck_document_version_drift_severity
        CHECK (drift_severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'));
COMMENT ON COLUMN document.document_versions.drift_severity IS
    'Nullable authoritative drift snapshot. NULL means unknown; S03 producer integration is separate.';

CREATE INDEX ix_versions_staff_queue ON document.document_versions (tenant_id, document_id)
    WHERE state = 'IN_REVIEW' AND review_stage = 'STAFF_REVIEW';
CREATE INDEX ix_submit_queue_latest ON document.submit_requests
    (tenant_id, document_version_id, resulting_revision DESC);

-- Execute as the application role so base-table RLS remains effective.
CREATE VIEW document.staff_review_queue WITH (security_invoker = true) AS
SELECT v.document_version_id AS version_id, v.tenant_id, v.document_id,
       d.title AS document_title, v.version_no, v.state, v.review_stage,
       c.workspace_id, d.repository_connection_id, tv.template_id,
       v.template_version_id, v.source_sha, v.drift_severity,
       CASE v.drift_severity WHEN 'LOW' THEN 1 WHEN 'MEDIUM' THEN 2
            WHEN 'HIGH' THEN 3 WHEN 'CRITICAL' THEN 4 END AS severity_rank,
       s.submitted_at, s.actor_identity_id AS submitted_by
FROM document.document_versions v
JOIN document.documents d ON d.tenant_id = v.tenant_id AND d.document_id = v.document_id
JOIN document.document_collections c ON c.tenant_id = d.tenant_id AND c.collection_id = d.collection_id
LEFT JOIN document.template_versions tv ON tv.tenant_id = v.tenant_id AND tv.template_version_id = v.template_version_id
LEFT JOIN LATERAL (
    SELECT r.submitted_at, r.actor_identity_id FROM document.submit_requests r
    WHERE r.tenant_id = v.tenant_id AND r.document_version_id = v.document_version_id
    ORDER BY r.resulting_revision DESC LIMIT 1
) s ON true
WHERE v.state = 'IN_REVIEW' AND v.review_stage = 'STAFF_REVIEW';
GRANT SELECT ON document.staff_review_queue TO ld_s04_app;
