-- LIV-164: preserve V1-V4 and legacy content references.
ALTER TABLE document.content_revisions ADD COLUMN content_markdown TEXT;
ALTER TABLE document.content_revisions ADD CONSTRAINT ck_content_markdown_length
    CHECK (content_markdown IS NULL OR char_length(content_markdown) <= 100000);

-- DOCUMENT_CREATED exists before the first Document Version.
ALTER TABLE document.document_audits ALTER COLUMN document_version_id DROP NOT NULL;
ALTER TABLE document.document_audits ALTER COLUMN new_state DROP NOT NULL;
ALTER TABLE document.document_audits ADD CONSTRAINT ck_document_audit_version_scope CHECK (
    (action = 'DOCUMENT_CREATED' AND document_version_id IS NULL
        AND previous_state IS NULL AND new_state IS NULL)
    OR (action <> 'DOCUMENT_CREATED' AND document_version_id IS NOT NULL AND new_state IS NOT NULL)
);

ALTER TABLE document.document_audits ADD CONSTRAINT fk_document_audits_document
    FOREIGN KEY (tenant_id, document_id) REFERENCES document.documents (tenant_id, document_id) ON DELETE RESTRICT;
