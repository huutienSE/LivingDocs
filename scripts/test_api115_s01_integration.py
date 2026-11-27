"""LIV-144: real Gateway/S01/S04/API-115 + Newman on disposable PostgreSQL/Redis.

Requires prebuilt service JARs, Java 21, Docker, Node/Newman 6.2.3, Python 3.12.
Never loads .env or deployment databases. SQL authority/Draft/severity fixtures
are controlled test setup, not production onboarding or S03/S02 proof.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import time
from urllib.error import URLError
from uuid import uuid4
import zipfile
from test_api113_s01_integration import ROOT, S01, S04, GATEWAY, java_executable, command, free_port, request, run_newman

COLLECTION = ROOT / "tests/postman/liv144-review-queue.postman_collection.json"


def main(scenario=None):
    api = scenario.API if scenario is not None else "api115"
    collection_path = scenario.COLLECTION if scenario is not None else COLLECTION
    parser = argparse.ArgumentParser(description=scenario.__doc__ if scenario is not None else __doc__)
    parser.add_argument("--newman-cli", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=S04 / f"target/{api}-s01-integration.json")
    args = parser.parse_args()
    jars = [p / f"target/{p.name}-0.0.1-SNAPSHOT.jar" for p in (S01, S04, GATEWAY)]
    if not all(p.is_file() for p in jars) or not args.newman_cli.is_file() or not shutil.which("node"):
        parser.error("Build S01/S04/Gateway JARs first and supply Newman bin/newman.js")
    args.output = args.output.resolve(); args.output.parent.mkdir(parents=True, exist_ok=True)
    report = {"preparedBy": "Codex", "startedAt": datetime.now(timezone.utc).isoformat(),
              "scope": "Real Gateway/S01 JWT + API-038/S04 API-115 and Submit/Return/resubmit; controlled SQL authority/Draft/severity fixtures; no FE/S02/S03 proof",
              "checks": [], "newman": [], "artifactsSha256": {
                  str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in [*jars, Path(__file__), Path(__file__).with_name("test_api113_s01_integration.py"), collection_path]}}
    if scenario is not None:
        report["scope"] = scenario.SCOPE
        report["artifactsSha256"][str(Path(scenario.__file__).relative_to(ROOT))] = hashlib.sha256(Path(scenario.__file__).read_bytes()).hexdigest()
    containers, processes, logs = [], [], []
    run_id = uuid4().hex[:10]
    java = java_executable()
    temporary = tempfile.TemporaryDirectory(prefix=f"ld-{api}-")
    temp = Path(temporary.name)

    def check(name, ok):
        if not ok: raise AssertionError(name)
        report["checks"].append({"name": name, "result": "PASS"})
        print("PASS " + name, flush=True)

    try:
        password = secrets.token_hex(24)
        for image, port, extra in [("postgres:17", 5432, ["-e", "POSTGRES_DB=livingdocs", "-e", "POSTGRES_PASSWORD=" + password]),
                                   ("redis:8.2-alpine", 6379, [])]:
            name = "ld-" + api + "-" + run_id + "-" + str(port)
            command("docker", "run", "--rm", "-d", "--name", name, "-p", f"127.0.0.1::{port}", *extra, image)
            containers.append(name)
        pg, redis = containers
        pg_port = command("docker", "port", pg, "5432/tcp").rsplit(":", 1)[1]
        redis_port = command("docker", "port", redis, "6379/tcp").rsplit(":", 1)[1]
        for _ in range(120):
            if subprocess.run(["docker", "exec", pg, "pg_isready", "-h", "127.0.0.1", "-U", "postgres", "-d", "livingdocs"], capture_output=True).returncode == 0: break
            time.sleep(0.5)
        else: raise RuntimeError("Disposable PostgreSQL did not start")

        def sql(text):
            return command("docker", "exec", "-i", pg, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "livingdocs", data=text)

        sql((S01 / "src/test/resources/s01-test-schema.sql").read_text(encoding="utf-8"))
        sql((S04 / "src/test/resources/db/bootstrap.sql").read_text(encoding="utf-8"))
        source = temp / "Keys.java"
        source.write_text('''import java.nio.file.*; import java.security.*; import java.util.*;
class Keys { public static void main(String[] a) throws Exception {
var g=KeyPairGenerator.getInstance("RSA"); g.initialize(2048); var p=g.generateKeyPair();
Files.writeString(Path.of(a[0]),"-----BEGIN PRIVATE KEY-----\\n"+Base64.getMimeEncoder().encodeToString(p.getPrivate().getEncoded())+"\\n-----END PRIVATE KEY-----\\n");
Files.writeString(Path.of(a[1]),"-----BEGIN PUBLIC KEY-----\\n"+Base64.getMimeEncoder().encodeToString(p.getPublic().getEncoded())+"\\n-----END PUBLIC KEY-----\\n"); }}''', encoding="utf-8")
        private, public, pepper = temp / "private.pem", temp / "public.pem", temp / "pepper"
        command(java, str(source), str(private), str(public)); pepper.write_text(secrets.token_hex(32), encoding="ascii")
        secret, hashed, registry = temp / "s04.secret", temp / "s04.hash", temp / "workloads.json"
        secret.write_text(secrets.token_urlsafe(32), encoding="ascii"); registry.write_text("[]", encoding="utf-8")
        with zipfile.ZipFile(jars[0]) as archive:
            entry = next(n for n in archive.namelist() if n.startswith("BOOT-INF/lib/spring-security-crypto-"))
            crypto = temp / Path(entry).name; crypto.write_bytes(archive.read(entry))
        hasher = temp / "Hash.java"
        hasher.write_text('''import java.nio.file.*; import org.springframework.security.crypto.bcrypt.BCrypt;
class Hash {public static void main(String[] a) throws Exception {Files.writeString(Path.of(a[1]),BCrypt.hashpw(Files.readString(Path.of(a[0])),BCrypt.gensalt()));}}''', encoding="utf-8")
        command(java, "--class-path", str(crypto), str(hasher), str(secret), str(hashed))
        ports = [free_port() for _ in range(4)]
        bases = [f"http://127.0.0.1:{p}" for p in ports[:3]]
        s01, s04, gateway = bases
        common = {"server.address": "127.0.0.1", "spring.config.import": "", "spring.profiles.active": "db",
                  "DB_URL": f"jdbc:postgresql://127.0.0.1:{pg_port}/livingdocs", "spring.jpa.hibernate.ddl-auto": "validate"}

        def start(index, options):
            logfile = args.output.parent / f"{api}-service-{index}.log"
            stream = logfile.open("w", encoding="utf-8"); logs.append(stream)
            environment = {k:v for k,v in os.environ.items() if not k.startswith(("DB_", "FLYWAY_", "SPRING_", "S01_", "S04_", "JWT_", "REDIS_", "LOGIN_RATE_"))}
            process = subprocess.Popen([java, "-jar", str(jars[index]), *[f"--{k}={v}" for k,v in {**common, **options}.items()]],
                                       cwd=temp, env=environment, stdout=stream, stderr=subprocess.STDOUT)
            processes.append(process)
            for _ in range(180):
                if process.poll() is not None: raise RuntimeError(f"Service {index} exited; inspect {logfile.name}")
                try:
                    if request(bases[index], "/api/v1/users/me/access-context" if index == 2 else "/actuator/health")[0] == (401 if index == 2 else 200): return process
                except (URLError, TimeoutError, ConnectionError): pass
                time.sleep(0.5)
            raise RuntimeError(f"Service {index} startup timed out")

        authority = start(0, {"server.port": ports[0], "DB_SCHEMA":"identity", "DB_USERNAME":"ld_s01_app", "DB_PASSWORD":"test_app_password",
                    "FLYWAY_USERNAME":"postgres", "FLYWAY_PASSWORD":password, "JWT_ISSUER":"urn:livingdocs:s01", "JWT_AUDIENCE":"livingdocs-api",
                    "JWT_PRIVATE_KEY_PATH":private, "JWT_PUBLIC_KEY_PATH":public, "LOGIN_RATE_LIMIT_PEPPER_PATH":pepper,
                    "REDIS_HOST":"127.0.0.1", "REDIS_PORT":redis_port, "livingdocs.security.workloads.registry-path":registry,
                    "livingdocs.security.workloads.allow-insecure-transport":"true"})
        start(1, {"server.port":ports[1], "DB_SCHEMA":"document", "DB_USERNAME":"ld_s04_app", "DB_PASSWORD":"test-only",
                  "FLYWAY_USERNAME":"ld_s04_migrator", "FLYWAY_PASSWORD":"test-only", "S01_ISSUER_URI":"urn:livingdocs:s01",
                  "S01_JWK_SET_URI":s01+"/.well-known/jwks.json", "S04_TOKEN_AUDIENCE":"livingdocs-api",
                  "S01_REVIEW_ACCESS_CONTEXT_URI":s01+"/api/v1/users/me/access-context", "S04_REVIEW_ALLOW_INSECURE_HTTP":"true",
                  "S01_SUBMIT_ACCESS_CONTEXT_URI":s01+"/api/v1/users/me/access-context", "S04_SUBMIT_ALLOW_INSECURE_HTTP":"true",
                  "S01_READ_ACCESS_CONTEXT_URI":s01+"/api/v1/users/me/access-context", "S04_READ_ALLOW_INSECURE_HTTP":"true",
                  "S01_AUTHORIZATION_SERVICE_TOKEN_URI":s01+"/internal/v1/auth/service-tokens",
                  "S01_AUTHORIZATION_EVALUATION_URI":s01+"/internal/v1/authorization/evaluate",
                  "S04_AUTHORIZATION_WORKLOAD_SECRET_PATH":secret, "S04_AUTHORIZATION_ALLOW_INSECURE_HTTP":"true"})
        start(2, {"server.port":ports[2], "management.server.port":ports[3], "spring.profiles.active":"",
                  "JWT_ISSUER_URI":"urn:livingdocs:s01", "JWT_JWK_SET_URI":s01+"/.well-known/jwks.json", "JWT_AUDIENCE":"livingdocs-api",
                  "S01_SERVICE_URI":s01, "S04_SERVICE_URI":s04})
        email, user_password = f"staff-{run_id}@example.test", secrets.token_urlsafe(24)+"aA1!"
        registered = request(s01, "/api/v1/auth/register", payload={"email":email,"password":user_password})
        check("Real S01 registration", registered[0] == 201)
        actor = registered[2]["data"]["identityId"]
        logged = request(s01, "/api/v1/auth/login", payload={"email":email,"password":user_password})
        check("Real S01 login", logged[0] == 200)
        token = logged[2]["data"]["accessToken"]
        tenant, workspace, membership, wm, repository, template, tv = [str(uuid4()) for _ in range(7)]
        registry.write_text(json.dumps([{"id":"s04","secretHash":hashed.read_text(),"audiences":["livingdocs-s01-internal"],
                                        "operations":["authorization:evaluate"],"tenantIds":[tenant]}]), encoding="utf-8")
        sql(f"""INSERT INTO identity.tenants(tenant_id,name) VALUES ('{tenant}','API115 test');
INSERT INTO identity.tenant_memberships(membership_id,tenant_id,identity_id) VALUES ('{membership}','{tenant}','{actor}');
INSERT INTO identity.workspaces(workspace_id,tenant_id,name) VALUES ('{workspace}','{tenant}','API115 test');
INSERT INTO identity.workspace_memberships(workspace_membership_id,tenant_id,workspace_id,tenant_membership_id,identity_id) VALUES ('{wm}','{tenant}','{workspace}','{membership}','{actor}');
INSERT INTO identity.workspace_role_assignments(tenant_id,workspace_id,workspace_membership_id,role_code) VALUES ('{tenant}','{workspace}','{wm}','STAFF'),('{tenant}','{workspace}','{wm}','DEVELOPER');
INSERT INTO identity.repository_grants(tenant_id,workspace_id,workspace_membership_id,repository_connection_id,provenance,operator_identity_id) VALUES ('{tenant}','{workspace}','{wm}','{repository}','CONTROLLED SQL TEST FIXTURE','{actor}');
UPDATE identity.repository_grants SET verification_status='VERIFIED',verification_source='OPERATOR_ATTESTED',verified_at=now() WHERE repository_connection_id='{repository}';
INSERT INTO document.document_templates(template_id,tenant_id,scope_type,scope_ref,name,document_type) VALUES ('{template}','{tenant}','WORKSPACE','{workspace}','API115','SDD');
INSERT INTO document.template_versions(template_version_id,tenant_id,template_id,version_no,structure_ref) VALUES ('{tv}','{tenant}','{template}',1,'test://structure');""")
        context = request(s01, "/api/v1/users/me/access-context", token)[2]["data"]
        check("API-038 returns real Staff read permissions and VERIFIED repository", context["tenantId"] == tenant and
              {"DOCUMENT_READ","WORKSPACE_VIEW"}.issubset(context["workspaceGrants"][0]["permissions"]) and repository in context["workspaceGrants"][0]["repositoryIds"])

        def fixture(t=tenant, w=workspace, repo=repository, severity="CRITICAL", templ=tv):
            collection, document, version, evidence = [str(uuid4()) for _ in range(4)]
            template_literal = "NULL" if templ is None else "'"+templ+"'"
            sql(f"""INSERT INTO document.document_collections(collection_id,tenant_id,workspace_id,name) VALUES ('{collection}','{t}','{w}','API115 fixture');
INSERT INTO document.documents(document_id,tenant_id,collection_id,repository_connection_id,title,ownership_ref) VALUES ('{document}','{t}','{collection}','{repo}','API115 candidate','test');
INSERT INTO document.document_versions(document_version_id,tenant_id,document_id,version_no,state,source_sha,creator_actor_ref,source_target_kind,source_target_ref,source_config_revision,source_captured_at,template_version_id,drift_severity) VALUES ('{version}','{t}','{document}',1,'DRAFT','{'a'*40}','test','TRACKED_BRANCH','refs/heads/main','1',now(),{template_literal},'{severity}');
INSERT INTO document.document_evidence_refs(document_evidence_id,tenant_id,document_version_id,evidence_type,source_ref) VALUES ('{evidence}','{t}','{version}','SOURCE','test://source');""")
            return version

        primary, secondary = fixture(), fixture(severity="LOW", templ=None)
        foreign_tenant, foreign_workspace, foreign_repo = [str(uuid4()) for _ in range(3)]
        hidden = [fixture(t=foreign_tenant, templ=None), fixture(w=foreign_workspace, templ=None), fixture(repo=foreign_repo, templ=None)]
        for version in hidden:
            sql(f"UPDATE document.document_versions SET state='IN_REVIEW',review_stage='STAFF_REVIEW' WHERE document_version_id='{version}';")
        extra_variables = scenario.prepare(sql, primary, secondary, hidden, actor, tenant, template, tv) if scenario is not None else {}
        for version in [primary, secondary]:
            submitted = request(gateway, f"/api/v1/document-versions/{version}/submit", token, {}, {"If-Match":f'"{version}:revision-0"',"Idempotency-Key":"api115-seed"})
            check("Real Submit enters Staff queue", submitted[0] == 200)
        variables = {"gatewayUrl":gateway,"accessToken":token,"versionId":primary,"secondaryVersionId":secondary,
                     "workspaceId":workspace,"repositoryId":repository,"templateId":template,
                     "foreignWorkspaceId":foreign_workspace,"foreignRepositoryId":foreign_repo}
        variables.update(extra_variables)

        def newman(folder): report["newman"].append(run_newman(folder, variables, args.newman_cli, temp, collection_path))
        if scenario is not None:
            scenario.run(sql=sql, check=check, newman=newman, variables=variables, authority=authority,
                         secret=secret, tenant=tenant, actor=actor, workspace_member=wm, s04=s04)
            report["result"] = "PASS"
            return
        def counts(): return sql("SELECT (SELECT sum(revision) FROM document.document_versions) || ':' || (SELECT count(*) FROM document.document_audits) || ':' || (SELECT count(*) FROM document.submit_requests) || ':' || (SELECT count(*) FROM document.review_decisions);")
        before = counts(); newman("01 Scoped queue")
        check("GETs preserve revisions, audit, submissions and decisions", counts() == before)
        original_secret = secret.read_text(); secret.write_text("invalid-workload-test", encoding="ascii")
        check("Queue read works without fresh/workload authorization", request(gateway,"/api/v1/review-queue",token)[0] == 200)
        secret.write_text(original_secret, encoding="ascii")
        check("Missing JWT denied at Gateway", request(gateway,"/api/v1/review-queue")[0] == 401)
        check("Tampered JWT denied at S04", request(s04,"/api/v1/review-queue",token+"x")[0] == 401)
        newman("02 Return and resubmit")
        check("Return/resubmit preserved real history", sql(f"SELECT revision || ':' || (SELECT count(*) FROM document.document_audits WHERE document_version_id='{primary}') FROM document.document_versions WHERE document_version_id='{primary}';") == "3:3")
        sql(f"UPDATE identity.repository_grants SET status='REVOKED',ended_at=now() WHERE repository_connection_id='{repository}';")
        newman("03 Revoked repository")
        sql(f"UPDATE identity.repository_grants SET status='ACTIVE',ended_at=NULL WHERE repository_connection_id='{repository}'; DELETE FROM identity.workspace_role_assignments WHERE workspace_membership_id='{wm}' AND role_code='STAFF';")
        newman("04 Revoked Staff")
        authority.terminate(); authority.wait(timeout=20)
        newman("05 S01 unavailable")
        report["result"] = "PASS"
    except Exception as error:
        report["result"] = "FAIL"; report["error"] = str(error)
        raise
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=20)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=10)
        for stream in logs: stream.close()
        for name in reversed(containers): command("docker","rm","-f",name)
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        print("Evidence: " + str(args.output), flush=True)
        temporary.cleanup()


if __name__ == "__main__": main()
