-- LIV-114 | S04 - Document Governance
-- Flyway V1: Document, Version, Review, Approval, Evidence
-- Schema owner: ld_s04_migrator
-- Runtime role: ld_s04_app

-- Protect Flyway metadata and future tables from runtime access.
REVOKE ALL PRIVILEGES
    ON TABLE document.flyway_schema_history
    FROM ld_s04_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA document
REVOKE ALL ON TABLES FROM ld_s04_app;

-- 1. DOCUMENT_TEMPLATE
-- Templates are scoped to a tenant or workspace.
-- scope_ref is a logical reference to S01.
-- các mẫu: Software Design Document, Test Plan,... có thể phạm vi tòàn tenant hoặc chỉ một workspace
CREATE TABLE document.document_templates (
    template_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    scope_type VARCHAR(16) NOT NULL, --phạm vi của template
    scope_ref UUID NOT NULL,

    name VARCHAR(255) NOT NULL,
    document_type VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_document_templates_scope_type
     CHECK (scope_type IN ('TENANT', 'WORKSPACE')),

    CONSTRAINT ck_document_templates_scope_ref
     CHECK (
         scope_type <> 'TENANT'
             OR scope_ref = tenant_id
         ),

    CONSTRAINT ck_document_templates_name
     CHECK (BTRIM(name) <> ''),

    CONSTRAINT ck_document_templates_type
     CHECK (BTRIM(document_type) <> ''),

    CONSTRAINT ck_document_templates_status
     CHECK (BTRIM(status) <> ''),

    CONSTRAINT uq_document_templates_tenant_id
     UNIQUE (tenant_id, template_id)
);

-- ------------------------------------------------------------
-- 2. TEMPLATE_VERSION
-- A template version is historical; create a new version
-- rather than overwriting a previously published version.
-- ------------------------------------------------------------

CREATE TABLE document.template_versions (
    template_version_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    template_id UUID NOT NULL,
    version_no INTEGER NOT NULL,

    structure_ref TEXT NOT NULL,
    formatting_rules JSONB NOT NULL DEFAULT '{}'::JSONB,
    published_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_template_versions_number
        CHECK (version_no > 0),

    CONSTRAINT ck_template_versions_structure_ref
        CHECK (BTRIM(structure_ref) <> ''),

    CONSTRAINT ck_template_versions_formatting_rules
        CHECK (jsonb_typeof(formatting_rules) = 'object'),

    CONSTRAINT uq_template_versions_tenant_id
        UNIQUE (tenant_id, template_version_id),

    CONSTRAINT uq_template_versions_number
        UNIQUE (tenant_id, template_id, version_no),

    CONSTRAINT fk_template_versions_template
        FOREIGN KEY (tenant_id, template_id)
            REFERENCES document.document_templates
                (tenant_id, template_id)
            ON DELETE RESTRICT
);

-- ------------------------------------------------------------
-- 3. DOCUMENT_COLLECTION
-- workspace_id belongs to S01: logical reference only.
-- default_template_version_id is S04-local FK.
-- nhóm các tài liệu trong cùng workspace để quan lý
-- ------------------------------------------------------------

CREATE TABLE document.document_collections (
    collection_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    workspace_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    archived_at TIMESTAMPTZ,

    default_template_version_id UUID,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_document_collections_name
       CHECK (BTRIM(name) <> ''),

    CONSTRAINT ck_document_collections_status
       CHECK (BTRIM(status) <> ''),

    CONSTRAINT uq_document_collections_tenant_id
       UNIQUE (tenant_id, collection_id),

    CONSTRAINT fk_document_collections_default_template
       FOREIGN KEY (tenant_id, default_template_version_id)
           REFERENCES document.template_versions
               (tenant_id, template_version_id)
           ON DELETE RESTRICT
);

-- ------------------------------------------------------------
-- 4. DOCUMENT
-- repository_connection_id belongs to S02: logical reference.
-- ------------------------------------------------------------

CREATE TABLE document.documents (
    document_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    collection_id UUID NOT NULL,
    repository_connection_id UUID,

    title VARCHAR(500) NOT NULL,
    ownership_ref VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_documents_title
        CHECK (BTRIM(title) <> ''),

    CONSTRAINT ck_documents_ownership_ref
        CHECK (BTRIM(ownership_ref) <> ''),

    CONSTRAINT ck_documents_status
        CHECK (BTRIM(status) <> ''),

    CONSTRAINT uq_documents_tenant_id
        UNIQUE (tenant_id, document_id),

    CONSTRAINT fk_documents_collection
        FOREIGN KEY (tenant_id, collection_id)
            REFERENCES document.document_collections
                (tenant_id, collection_id)
            ON DELETE RESTRICT
);

-- ------------------------------------------------------------
-- 5. DOCUMENT_VERSION
-- Official states are defined by REQ-05.
-- IN_REVIEW is a state with STAFF_REVIEW/MANAGER_APPROVAL stage.
-- ------------------------------------------------------------

CREATE TABLE document.document_versions (
    document_version_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    document_id UUID NOT NULL,
    version_no INTEGER NOT NULL,

    state VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    review_stage VARCHAR(32),

    source_sha VARCHAR(128),
    template_version_id UUID,

    creator_actor_ref VARCHAR(255) NOT NULL,
    initiated_by_identity_id UUID,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_document_versions_number
        CHECK (version_no > 0),

    CONSTRAINT ck_document_versions_state
        CHECK (
            state IN (
                      'DRAFT',
                      'IN_REVIEW',
                      'APPROVED',
                      'PUBLISHED',
                      'NEEDS_REVALIDATION',
                      'REJECTED',
                      'SUPERSEDED'
                )
            ),

    CONSTRAINT ck_document_versions_review_stage
        CHECK (
            (
                state = 'IN_REVIEW'
                    AND review_stage IN ('STAFF_REVIEW', 'MANAGER_APPROVAL')
                )
                OR
            (
                state <> 'IN_REVIEW'
                    AND review_stage IS NULL
                )
            ),

    CONSTRAINT ck_document_versions_creator
        CHECK (BTRIM(creator_actor_ref) <> ''),

    CONSTRAINT uq_document_versions_tenant_id
        UNIQUE (tenant_id, document_version_id),

    CONSTRAINT uq_document_versions_number
        UNIQUE (tenant_id, document_id, version_no),

    CONSTRAINT uq_document_versions_document_reference
        UNIQUE (tenant_id, document_id, document_version_id),

    CONSTRAINT fk_document_versions_document
        FOREIGN KEY (tenant_id, document_id)
            REFERENCES document.documents
                (tenant_id, document_id)
            ON DELETE RESTRICT,

    CONSTRAINT fk_document_versions_template
        FOREIGN KEY (tenant_id, template_version_id)
            REFERENCES document.template_versions
                (tenant_id, template_version_id)
            ON DELETE RESTRICT
);

-- ------------------------------------------------------------
-- 6. CONTENT_REVISION
-- content_ref is a safe reference to stored content/artifact.
-- editor_identity_id belongs to S01: logical reference only.
-- ------------------------------------------------------------

CREATE TABLE document.content_revisions (
    content_revision_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    document_version_id UUID NOT NULL,
    revision_no INTEGER NOT NULL,

    content_ref TEXT NOT NULL,
    editor_identity_id UUID NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_content_revisions_number
        CHECK (revision_no > 0),

    CONSTRAINT ck_content_revisions_content_ref
        CHECK (BTRIM(content_ref) <> ''),

    CONSTRAINT uq_content_revisions_tenant_id
        UNIQUE (tenant_id, content_revision_id),

    CONSTRAINT uq_content_revisions_number
        UNIQUE (tenant_id, document_version_id, revision_no),

    CONSTRAINT fk_content_revisions_version
        FOREIGN KEY (tenant_id, document_version_id)
            REFERENCES document.document_versions
                (tenant_id, document_version_id)
            ON DELETE RESTRICT
);

-- ------------------------------------------------------------
-- 7. REVIEW_DECISION
-- Staff outcomes: PASS or RETURN_FOR_REVISION.
-- Staff does not perform terminal REJECT.
-- ------------------------------------------------------------

CREATE TABLE document.review_decisions (
    review_decision_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    document_version_id UUID NOT NULL,
    reviewer_identity_id UUID NOT NULL,

    decision VARCHAR(32) NOT NULL,
    reason TEXT,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    round_no INTEGER NOT NULL,

    CONSTRAINT ck_review_decisions_decision
       CHECK (decision IN ('PASS', 'RETURN_FOR_REVISION')),

    CONSTRAINT ck_review_decisions_reason
       CHECK (
           decision <> 'RETURN_FOR_REVISION'
               OR NULLIF(BTRIM(reason), '') IS NOT NULL
           ),

    CONSTRAINT ck_review_decisions_round
       CHECK (round_no > 0),

    CONSTRAINT uq_review_decisions_tenant_id
       UNIQUE (tenant_id, review_decision_id),

    CONSTRAINT fk_review_decisions_version
       FOREIGN KEY (tenant_id, document_version_id)
           REFERENCES document.document_versions
               (tenant_id, document_version_id)
           ON DELETE RESTRICT
);

-- ------------------------------------------------------------
-- 8. APPROVAL_DECISION
-- Manager outcomes follow REQ-05 transition semantics.
-- ------------------------------------------------------------

CREATE TABLE document.approval_decisions (
    approval_decision_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    document_version_id UUID NOT NULL,
    approver_identity_id UUID NOT NULL,

    decision VARCHAR(32) NOT NULL,
    reason TEXT,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    round_no INTEGER NOT NULL,

    CONSTRAINT ck_approval_decisions_decision
     CHECK (
         decision IN (
                      'FINAL_APPROVE',
                      'RETURN_TO_STAFF',
                      'RETURN_TO_DEVELOPER',
                      'REJECT'
             )
         ),

    CONSTRAINT ck_approval_decisions_reason
     CHECK (
         decision = 'FINAL_APPROVE'
             OR NULLIF(BTRIM(reason), '') IS NOT NULL
         ),

    CONSTRAINT ck_approval_decisions_round
     CHECK (round_no > 0),

    CONSTRAINT uq_approval_decisions_tenant_id
     UNIQUE (tenant_id, approval_decision_id),

    CONSTRAINT fk_approval_decisions_version
     FOREIGN KEY (tenant_id, document_version_id)
         REFERENCES document.document_versions
             (tenant_id, document_version_id)
         ON DELETE RESTRICT
);

-- ------------------------------------------------------------
-- 9. REVIEW_COMMENT
-- actor_identity_id belongs to S01: logical reference only.
-- ------------------------------------------------------------

CREATE TABLE document.review_comments (
    review_comment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    document_version_id UUID NOT NULL,
    actor_identity_id UUID NOT NULL,

    section_ref VARCHAR(255),
    message TEXT NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_review_comments_message
      CHECK (BTRIM(message) <> ''),

    CONSTRAINT uq_review_comments_tenant_id
      UNIQUE (tenant_id, review_comment_id),

    CONSTRAINT fk_review_comments_version
      FOREIGN KEY (tenant_id, document_version_id)
          REFERENCES document.document_versions
              (tenant_id, document_version_id)
          ON DELETE RESTRICT
);

-- ------------------------------------------------------------
-- 10. REVALIDATION_RECORD
-- validation_id belongs to S02: logical reference only.
-- ------------------------------------------------------------

CREATE TABLE document.revalidation_records (
    revalidation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    document_version_id UUID NOT NULL,
    validation_id UUID NOT NULL,

    prior_stage VARCHAR(32) NOT NULL,
    outcome VARCHAR(32) NOT NULL,

    checked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_revalidation_prior_stage
       CHECK (
           prior_stage IN (
                           'DRAFT',
                           'STAFF_REVIEW',
                           'MANAGER_APPROVAL',
                           'APPROVED'
               )
           ),

    CONSTRAINT ck_revalidation_outcome
       CHECK (
           outcome IN (
                       'NO_RELEVANT_IMPACT',
                       'RELEVANT_IMPACT'
               )
           ),

    CONSTRAINT uq_revalidation_records_tenant_id
       UNIQUE (tenant_id, revalidation_id),

    CONSTRAINT fk_revalidation_records_version
       FOREIGN KEY (tenant_id, document_version_id)
           REFERENCES document.document_versions
               (tenant_id, document_version_id)
           ON DELETE RESTRICT
);

-- ------------------------------------------------------------
-- 11. DOCUMENT_EVIDENCE_REF
-- External source/artifact references; do not duplicate raw source.
-- ------------------------------------------------------------

CREATE TABLE document.document_evidence_refs (
    document_evidence_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    document_version_id UUID NOT NULL,

    evidence_type VARCHAR(64) NOT NULL,
    source_ref TEXT,
    artifact_ref TEXT,

    attached_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_document_evidence_type
     CHECK (BTRIM(evidence_type) <> ''),

    CONSTRAINT ck_document_evidence_reference
     CHECK (
         source_ref IS NOT NULL
             OR artifact_ref IS NOT NULL
         ),

    CONSTRAINT uq_document_evidence_tenant_id
     UNIQUE (tenant_id, document_evidence_id),

    CONSTRAINT fk_document_evidence_version
     FOREIGN KEY (tenant_id, document_version_id)
         REFERENCES document.document_versions
             (tenant_id, document_version_id)
         ON DELETE RESTRICT
);

-- ------------------------------------------------------------
-- 12. DOCUMENT_AUDIT
-- Snapshot resource scope and source context for traceability.
-- workspace_id and repository_connection_id are logical refs.
-- ------------------------------------------------------------

CREATE TABLE document.document_audits (
    document_audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,

    document_id UUID NOT NULL,
    document_version_id UUID NOT NULL,

    workspace_id UUID NOT NULL,
    repository_connection_id UUID,

    actor_ref VARCHAR(255) NOT NULL,
    action VARCHAR(100) NOT NULL,

    previous_state VARCHAR(32),
    new_state VARCHAR(32) NOT NULL,

    source_sha VARCHAR(128),
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,

    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_document_audits_actor
      CHECK (BTRIM(actor_ref) <> ''),

    CONSTRAINT ck_document_audits_action
      CHECK (BTRIM(action) <> ''),

    CONSTRAINT ck_document_audits_previous_state
      CHECK (
          previous_state IS NULL
              OR previous_state IN (
                                    'DRAFT',
                                    'IN_REVIEW',
                                    'APPROVED',
                                    'PUBLISHED',
                                    'NEEDS_REVALIDATION',
                                    'REJECTED',
                                    'SUPERSEDED'
              )
          ),

    CONSTRAINT ck_document_audits_new_state
      CHECK (
          new_state IN (
                        'DRAFT',
                        'IN_REVIEW',
                        'APPROVED',
                        'PUBLISHED',
                        'NEEDS_REVALIDATION',
                        'REJECTED',
                        'SUPERSEDED'
              )
          ),

    CONSTRAINT ck_document_audits_metadata
      CHECK (jsonb_typeof(metadata) = 'object'),

    CONSTRAINT uq_document_audits_tenant_id
      UNIQUE (tenant_id, document_audit_id),

    CONSTRAINT fk_document_audits_version
      FOREIGN KEY (tenant_id, document_id, document_version_id)
          REFERENCES document.document_versions
              (tenant_id, document_id, document_version_id)
          ON DELETE RESTRICT
);

-- ------------------------------------------------------------
-- 13. INDEXES
-- ------------------------------------------------------------

CREATE INDEX idx_document_collections_workspace
    ON document.document_collections (tenant_id, workspace_id);

CREATE INDEX idx_documents_collection
    ON document.documents (tenant_id, collection_id);

CREATE INDEX idx_documents_repository
    ON document.documents (tenant_id, repository_connection_id)
    WHERE repository_connection_id IS NOT NULL;

CREATE INDEX idx_document_versions_timeline
    ON document.document_versions
        (tenant_id, document_id, created_at DESC);

CREATE INDEX idx_content_revisions_timeline
    ON document.content_revisions
        (tenant_id, document_version_id, revision_no DESC);

CREATE INDEX idx_review_decisions_timeline
    ON document.review_decisions
        (tenant_id, document_version_id, round_no DESC, decided_at DESC);

CREATE INDEX idx_approval_decisions_timeline
    ON document.approval_decisions
        (tenant_id, document_version_id, round_no DESC, decided_at DESC);

CREATE INDEX idx_review_comments_timeline
    ON document.review_comments
        (tenant_id, document_version_id, created_at DESC);

CREATE INDEX idx_revalidation_records_timeline
    ON document.revalidation_records
        (tenant_id, document_version_id, checked_at DESC);

CREATE INDEX idx_document_evidence_timeline
    ON document.document_evidence_refs
        (tenant_id, document_version_id, attached_at DESC);

CREATE INDEX idx_document_audits_timeline
    ON document.document_audits
        (tenant_id, document_id, occurred_at DESC);

-- ------------------------------------------------------------
-- 14. ROW-LEVEL SECURITY
-- ------------------------------------------------------------

ALTER TABLE document.document_templates
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE document.template_versions
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE document.document_collections
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE document.documents
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE document.document_versions
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE document.content_revisions
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE document.review_decisions
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE document.approval_decisions
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE document.review_comments
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE document.revalidation_records
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE document.document_evidence_refs
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE document.document_audits
    ENABLE ROW LEVEL SECURITY;

CREATE POLICY document_templates_tenant_isolation
ON document.document_templates
FOR ALL TO ld_s04_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY template_versions_tenant_isolation
ON document.template_versions
FOR ALL TO ld_s04_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY document_collections_tenant_isolation
ON document.document_collections
FOR ALL TO ld_s04_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY documents_tenant_isolation
ON document.documents
FOR ALL TO ld_s04_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY document_versions_tenant_isolation
ON document.document_versions
FOR ALL TO ld_s04_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY content_revisions_tenant_isolation
ON document.content_revisions
FOR ALL TO ld_s04_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY review_decisions_tenant_isolation
ON document.review_decisions
FOR ALL TO ld_s04_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY approval_decisions_tenant_isolation
ON document.approval_decisions
FOR ALL TO ld_s04_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY review_comments_tenant_isolation
ON document.review_comments
FOR ALL TO ld_s04_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY revalidation_records_tenant_isolation
ON document.revalidation_records
FOR ALL TO ld_s04_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY document_evidence_tenant_isolation
ON document.document_evidence_refs
FOR ALL TO ld_s04_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

CREATE POLICY document_audits_tenant_isolation
ON document.document_audits
FOR ALL TO ld_s04_app
USING (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
)
WITH CHECK (
    tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID
);

-- ------------------------------------------------------------
-- 15. RUNTIME PRIVILEGES
-- Mutable aggregates: SELECT / INSERT / UPDATE.
-- Version history, decisions and evidence: append-only.
-- No hard DELETE privilege is granted.
-- ------------------------------------------------------------

GRANT USAGE ON SCHEMA document TO ld_s04_app;

GRANT SELECT, INSERT, UPDATE
      ON document.document_templates,
          document.document_collections,
          document.documents,
          document.document_versions
TO ld_s04_app;

GRANT SELECT, INSERT
      ON document.template_versions,
          document.content_revisions,
          document.review_decisions,
          document.approval_decisions,
          document.review_comments,
          document.revalidation_records,
          document.document_evidence_refs,
          document.document_audits
TO ld_s04_app;
