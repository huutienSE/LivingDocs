"""LIV-145: real Gateway/S01/S04 API-116 and ETag -> API-120 Return -> resubmit.

Use prebuilt S01/S04/Gateway JARs, Java 21, Docker, Python 3.12 and Newman 6.2.3.
The shared disposable harness never reads .env or application databases. Canonical
content/template/baseline/evidence and VERIFIED grants are controlled SQL fixtures;
this is not S02 artifact retrieval, GitHub source validation, FE or authoring E2E proof.
"""
import sys
import re
from uuid import uuid4
from test_api113_s01_integration import ROOT, request
from test_api115_s01_integration import main

API = "api116"
COLLECTION = ROOT / "tests/postman/liv145-review-context.postman_collection.json"
SCOPE = "Real Gateway/S01 JWT + API-038/S04 API-116 with canonical snapshots, metadata invalidation, wildcard scope checks and context ETag/Return/resubmit; SQL content/template/evidence/authority fixtures; no FE/S02/S03/artifact-reader proof"


def prepare(sql, primary, secondary, hidden, actor, tenant, template, tv):
    baseline, evidence = str(uuid4()), str(uuid4())
    document = sql(f"SELECT document_id FROM document.document_versions WHERE document_version_id='{primary}';")
    sql(f"""UPDATE document.document_versions SET version_no=3 WHERE document_version_id='{primary}';
INSERT INTO document.content_revisions(tenant_id,document_version_id,revision_no,content_ref,content_text,content_format,editor_identity_id) VALUES
('{tenant}','{primary}',1,'artifact://original','# Original body','MARKDOWN','{actor}'),
('{tenant}','{primary}',2,'artifact://current','# Current candidate body','MARKDOWN','{actor}');
INSERT INTO document.content_revisions(tenant_id,document_version_id,revision_no,content_ref,editor_identity_id) VALUES ('{tenant}','{secondary}',1,'artifact://legacy-reference','{actor}');
INSERT INTO document.document_versions(document_version_id,tenant_id,document_id,version_no,state,creator_actor_ref) VALUES ('{baseline}','{tenant}','{document}',2,'PUBLISHED','controlled-fixture');
INSERT INTO document.content_revisions(tenant_id,document_version_id,revision_no,content_ref,content_text,content_format,editor_identity_id) VALUES ('{tenant}','{baseline}',1,'artifact://published','Published baseline body','TEXT','{actor}');
UPDATE document.template_versions SET formatting_rules='{{"headings":["Overview"]}}'::jsonb WHERE template_version_id='{tv}';
INSERT INTO document.document_evidence_refs(document_evidence_id,tenant_id,document_version_id,evidence_type,source_ref) VALUES ('{evidence}','{tenant}','{primary}','DIFF','source://pinned-diff');""")
    return {"baselineVersionId":baseline, "templateVersionId":tv, "diffEvidenceId":evidence,
            "foreignTenantVersionId":hidden[0], "foreignWorkspaceVersionId":hidden[1], "foreignRepositoryVersionId":hidden[2],
            "missingVersionId":str(uuid4())}


def run(*, sql, check, newman, variables, authority, secret, tenant, actor, workspace_member, s04):
    gateway, token, version = variables["gatewayUrl"], variables["accessToken"], variables["versionId"]
    path = f"/api/v1/document-versions/{version}/review-context"

    def fingerprint():
        return sql("SELECT (SELECT sum(revision) FROM document.document_versions) || ':' || (SELECT count(*) FROM document.content_revisions) || ':' || (SELECT count(*) FROM document.document_audits) || ':' || (SELECT count(*) FROM document.submit_requests) || ':' || (SELECT count(*) FROM document.review_decisions);")

    initial = request(gateway, path, token)
    initial_etag = next((value for key, value in initial[1].items() if key.lower() == "etag"), None)
    check("Initial context ETag identifies the exact version/revision", initial[0] == 200
          and re.fullmatch(rf'"{version}:revision-1:context-[0-9a-f]{{64}}"', initial_etag or "") is not None
          and initial[2]["data"]["version"]["revision"] == 1)
    # Each Newman phase is a separate process/environment; explicitly pass the actual HTTP ETag across phases.
    variables["initialContextEtag"] = initial_etag
    variables["contextEtag"] = initial_etag
    before = fingerprint()
    newman("01 Canonical context")
    check("Context GETs preserve version/content/audit/submission/decision state", fingerprint() == before)
    sql(f"UPDATE document.document_templates SET name='Updated template metadata' WHERE template_id=(SELECT template_id FROM document.template_versions WHERE template_version_id='{variables['templateVersionId']}');")
    newman("02 Context metadata invalidation")
    changed = request(gateway, path, token)
    changed_etag = next((value for key, value in changed[1].items() if key.lower() == "etag"), None)
    check("Template metadata changes context ETag without changing candidate revision or review state",
          changed[0] == 200 and changed_etag != initial_etag
          and changed[2]["data"]["template"]["name"] == "Updated template metadata"
          and changed[2]["data"]["version"]["revision"] == 1 and fingerprint() == before)
    variables["contextEtag"] = changed_etag
    original_secret = secret.read_text(); secret.write_text("invalid-workload-test", encoding="ascii")
    check("API-116 reads do not depend on workload/fresh authorization", request(gateway,path,token)[0] == 200)
    secret.write_text(original_secret, encoding="ascii")
    check("Gateway denies a missing JWT", request(gateway,path)[0] == 401)
    check("S04 independently denies tampered JWT", request(s04,path,token+"x")[0] == 401)
    newman("02 Context ETag and decision")
    current = request(gateway, path, token)
    current_etag = next((value for key, value in current[1].items() if key.lower() == "etag"), None)
    check("Refreshed context ETag advances after Return/resubmit", current[0] == 200
          and re.fullmatch(rf'"{version}:revision-3:context-[0-9a-f]{{64}}"', current_etag or "") is not None
          and current[2]["data"]["version"]["revision"] == 3)
    variables["contextEtag"] = current_etag
    check("Context ETag -> Return -> resubmit records exactly one review and three audits", sql(f"SELECT revision || ':' || (SELECT count(*) FROM document.review_decisions WHERE document_version_id='{version}') || ':' || (SELECT count(*) FROM document.document_audits WHERE document_version_id='{version}') FROM document.document_versions WHERE document_version_id='{version}';") == "3:1:3")
    repository = variables["repositoryId"]
    sql(f"UPDATE identity.repository_grants SET status='REVOKED',ended_at=now() WHERE repository_connection_id='{repository}';")
    newman("03 Revoked repository")
    sql(f"UPDATE identity.repository_grants SET status='ACTIVE',ended_at=NULL WHERE repository_connection_id='{repository}'; DELETE FROM identity.workspace_role_assignments WHERE workspace_membership_id='{workspace_member}' AND role_code='STAFF';")
    newman("04 Revoked Staff")
    authority.terminate(); authority.wait(timeout=20)
    newman("05 S01 unavailable")


if __name__ == "__main__": main(scenario=sys.modules[__name__])
