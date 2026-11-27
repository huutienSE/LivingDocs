-- LIV-145: store real content with its immutable content revision; never interpret a ref as body text.
ALTER TABLE document.content_revisions
    ADD COLUMN content_text TEXT,
    ADD COLUMN content_format VARCHAR(16),
    ADD CONSTRAINT ck_content_revision_snapshot CHECK (
        (content_text IS NULL AND content_format IS NULL)
        OR (content_text IS NOT NULL AND content_format IS NOT NULL AND content_format IN ('MARKDOWN', 'TEXT'))
    );
COMMENT ON COLUMN document.content_revisions.content_text IS
    'Canonical optional content snapshot; NULL for legacy references without materialized content. Writers append revisions.';
COMMENT ON COLUMN document.content_revisions.content_format IS
    'Format of content_text; unknown legacy snapshots remain NULL.';
