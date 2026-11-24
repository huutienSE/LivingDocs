-- LIV-138 PR2: V5 changed global role permissions. Existing tenants need a new
-- authority revision and audit record so earlier snapshots cannot appear current.
-- V5 has already run on local databases; keep its checksum immutable.
WITH revised_tenants AS (
    UPDATE identity.tenants
       SET authz_revision = authz_revision + 1
     RETURNING tenant_id, authz_revision
)
INSERT INTO identity.authorization_audit
    (tenant_id, authz_revision, actor_identity_id, db_actor_name,
     action, target_table, target_id, reason)
SELECT tenant_id, authz_revision, NULL, session_user,
       'CATALOG_CHANGED', 'tenants', tenant_id,
       'V5 REPOSITORY_CONNECT mapping added for DEVELOPER and TECHNICAL_LEAD'
  FROM revised_tenants;
