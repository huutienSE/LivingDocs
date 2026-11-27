"""Run real S01 login/API-038 -> S04 API-113 against disposable PostgreSQL/Redis.

Build service jars first. Requires Java 21, Docker and Python 3.12; no Python
packages. --gateway-newman also needs the Gateway JAR and Newman 6.2.3/Node.
The Return/resubmit phase uses real Service JWT/API-039 and a controlled SQL
VERIFIED grant fixture; it does not prove production grant onboarding or S02.
Never reads .env or uses the developer database. Authority SQL below is
an isolated test fixture, not a supported operator provisioning workflow.
"""
import argparse
import base64
import hashlib
from datetime import datetime, timezone
import json
import os
import re
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import zipfile
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
S01 = ROOT / "backend/identity-organization-service"
S04 = ROOT / "backend/document-governance-service"
GATEWAY = ROOT / "infrastructure/api-gateway"
COLLECTION = ROOT / "tests/postman/liv143-submit.postman_collection.json"


def java_executable():
    # Windows javapath may be a launcher that leaves a child JVM alive after
    # Popen.terminate(). Launch the actual JVM so outage tests and cleanup work.
    result = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True, text=True)
    match = re.search(r"^\s*java.home\s*=\s*(.+)$", result.stderr, re.MULTILINE)
    if result.returncode or not match:
        raise RuntimeError("Cannot locate the actual Java runtime")
    return str(Path(match.group(1).strip()) / "bin" / ("java.exe" if os.name == "nt" else "java"))


def command(*args, data=None):
    result = subprocess.run(args, input=data, text=True, encoding="utf-8", capture_output=True)
    if result.returncode:
        # Commands never contain a user JWT or a real deployment credential.
        raise RuntimeError(f"{args[0]} failed: {result.stderr[-2000:]}")
    return result.stdout.strip()


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def request(base, path, token=None, payload=None, headers=None):
    actual = dict(headers or {})
    if token:
        actual["Authorization"] = "Bearer " + token
    if payload is not None:
        actual["Content-Type"] = "application/json"
    req = Request(base + path, data=None if payload is None else json.dumps(payload).encode(), headers=actual)
    try:
        response = urlopen(req, timeout=10)
    except HTTPError as error:
        response = error
    with response:
        body = response.read().decode()
        return response.status, dict(response.headers), json.loads(body) if body else None


def run_newman(folder, variables, cli, temporary):
    """Keep credentials and raw Newman response data only in the temporary directory."""
    collection = json.loads(COLLECTION.read_text(encoding="utf-8"))
    expected = len(next(group for group in collection["item"] if group["name"] == folder)["item"])
    environment = temporary / "newman-environment.json"
    raw_report = temporary / "newman-report.json"
    environment.write_text(json.dumps({"name": "disposable-liv143", "values": [
        {"key": key, "value": value, "enabled": True, "type": "secret"}
        for key, value in variables.items()]}), encoding="utf-8")
    environment.chmod(0o600)
    result = subprocess.run([shutil.which("node"), str(cli), "run", str(COLLECTION),
                             "--folder", folder, "--environment", str(environment),
                             "--timeout-request", "10000", "--timeout", "120000",
                             "--reporters", "json", "--reporter-json-export", str(raw_report)],
                            capture_output=True, timeout=150)
    if not raw_report.is_file():
        raise RuntimeError("Newman did not produce a report; output withheld to protect credentials")
    raw = json.loads(raw_report.read_text(encoding="utf-8"))["run"]
    stats = raw["stats"]
    skipped = sum(bool(assertion.get("skipped")) for execution in raw["executions"]
                  for assertion in execution.get("assertions", []))
    summary = {"folder": folder, "requests": stats["requests"]["total"],
               "assertions": stats["assertions"]["total"], "failedAssertions": stats["assertions"]["failed"],
               "skippedAssertions": skipped, "result": "FAIL"}
    if (result.returncode or summary["requests"] != expected or summary["assertions"] < expected
            or skipped or raw.get("failures") or stats["requests"]["failed"]):
        # Avoid copying raw failure messages/headers/bodies that may contain a token.
        failures = [{"request": f.get("source", {}).get("name"), "errorType": f.get("error", {}).get("name")}
                    for f in raw.get("failures", [])]
        raise AssertionError("Newman phase failed: " + json.dumps({"summary": summary, "failures": failures}))
    raw_report.unlink()
    environment.unlink()
    summary["result"] = "PASS"
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=S04 / "target/api113-s01-integration.json")
    parser.add_argument("--gateway-newman", action="store_true", help="Also run the real Gateway and all API-113 Newman phases")
    parser.add_argument("--newman-cli", type=Path, help="Path to node_modules/newman/bin/newman.js (Newman 6.2.3)")
    args = parser.parse_args()
    jars = [service / f"target/{service.name}-0.0.1-SNAPSHOT.jar" for service in (S01, S04)]
    if args.gateway_newman:
        jars.append(GATEWAY / "target/api-gateway-0.0.1-SNAPSHOT.jar")
        if args.newman_cli is None or not args.newman_cli.is_file() or shutil.which("node") is None:
            raise SystemExit("--gateway-newman requires Node and --newman-cli pointing to Newman 6.2.3 bin/newman.js")
    if not all(path.is_file() for path in jars):
        raise SystemExit("Build both services with mvn -B -ntp package first.")
    report = {"preparedBy": "Codex", "startedAt": datetime.now(timezone.utc).isoformat(),
              "scope": "Real S01 Identity JWT + API-038; S04 API-113. Disposable authority fixtures; no S02/Gateway.",
              "fixtureMode": "CONTROLLED SQL authority/VERIFIED-grant fixture, not production Grant lifecycle or S02 binding proof",
              "artifactsSha256": {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                                  for path in [*jars, Path(__file__).resolve()]},
              "checks": []}
    if args.gateway_newman:
        report["scope"] = "Real Gateway/S01/S04 + API-113/API-120 Newman, Service JWT/API-039 for Staff Return; disposable authority/Draft/VERIFIED-grant fixtures; no S02."
        report["newman"] = []
        report["artifactsSha256"][str(COLLECTION.relative_to(ROOT))] = hashlib.sha256(COLLECTION.read_bytes()).hexdigest()
    containers, processes, logs = [], [], []
    run_id = uuid4().hex[:12]
    args.output = args.output.resolve()
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        report["checks"].append({"name": name, "result": "PASS"})
        print("PASS " + name, flush=True)

    temporary = tempfile.TemporaryDirectory(prefix="ld-api113-")
    pg_password = secrets.token_hex(24)
    try:
        java = java_executable()
        temp = Path(temporary.name)
        for image, port, extra in (("postgres:17", 5432, ["-e", "POSTGRES_DB=livingdocs", "-e", "POSTGRES_PASSWORD=" + pg_password]),
                                   ("redis:8.2-alpine", 6379, [])):
            name = "ld-api113-" + run_id + "-" + str(port)
            command("docker", "run", "--rm", "-d", "--name", name, "-p", f"127.0.0.1::{port}", *extra, image)
            containers.append(name)
        pg, redis = containers
        pg_port = int(command("docker", "port", pg, "5432/tcp").rsplit(":", 1)[1])
        redis_port = int(command("docker", "port", redis, "6379/tcp").rsplit(":", 1)[1])
        for _ in range(120):
            ready = subprocess.run(["docker", "exec", pg, "pg_isready", "-h", "127.0.0.1", "-U", "postgres", "-d", "livingdocs"], capture_output=True)
            if ready.returncode == 0:
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("Disposable PostgreSQL did not become ready")

        def sql(text):
            return command("docker", "exec", "-i", pg, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "livingdocs", data=text)

        sql((S01 / "src/test/resources/s01-test-schema.sql").read_text(encoding="utf-8"))
        sql((S04 / "src/test/resources/db/bootstrap.sql").read_text(encoding="utf-8"))
        # Generate a private key owned only by this isolated S01 process.
        source = temp / "TestKeys.java"
        source.write_text('''import java.nio.file.*; import java.security.*; import java.util.*;
class TestKeys { public static void main(String[] a) throws Exception {
var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair=generator.generateKeyPair();
Files.writeString(Path.of(a[0]), "-----BEGIN PRIVATE KEY-----\\n"+Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())+"\\n-----END PRIVATE KEY-----\\n");
Files.writeString(Path.of(a[1]), "-----BEGIN PUBLIC KEY-----\\n"+Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())+"\\n-----END PUBLIC KEY-----\\n"); }}''', encoding="utf-8")
        private, public, pepper = temp / "private.pem", temp / "public.pem", temp / "pepper"
        command(java, str(source), str(private), str(public))
        pepper.write_text(secrets.token_hex(32), encoding="ascii")
        workload_secret, workload_hash, registry = temp / "s04.secret", temp / "s04.hash", temp / "workloads.json"
        workload_secret.write_text(secrets.token_urlsafe(32), encoding="ascii")
        registry.write_text("[]", encoding="utf-8")
        with zipfile.ZipFile(jars[0]) as archive:
            entry = next(name for name in archive.namelist() if name.startswith("BOOT-INF/lib/spring-security-crypto-"))
            crypto = temp / Path(entry).name
            crypto.write_bytes(archive.read(entry))
        hasher = temp / "HashWorkload.java"
        hasher.write_text('''import java.nio.file.*; import org.springframework.security.crypto.bcrypt.BCrypt;
class HashWorkload { public static void main(String[] a) throws Exception {
Files.writeString(Path.of(a[1]), BCrypt.hashpw(Files.readString(Path.of(a[0])), BCrypt.gensalt())); }}''', encoding="utf-8")
        command(java, "--class-path", str(crypto), str(hasher), str(workload_secret), str(workload_hash))
        s01_port, s04_port, gateway_port, gateway_management_port = [free_port() for _ in range(4)]
        s01, s04 = f"http://127.0.0.1:{s01_port}", f"http://127.0.0.1:{s04_port}"
        gateway = f"http://127.0.0.1:{gateway_port}"
        common = {"spring.profiles.active": "db", "server.address": "127.0.0.1",
                  "DB_URL": f"jdbc:postgresql://127.0.0.1:{pg_port}/livingdocs",
                  "spring.config.import": "", "spring.jpa.hibernate.ddl-auto": "validate"}

        def start(index, options):
            logfile = args.output.parent / f"api113-s01-integration-{index}.log"
            stream = logfile.open("w", encoding="utf-8")
            logs.append(stream)
            environment = {key: value for key, value in os.environ.items()
                           if not key.startswith(("DB_", "FLYWAY_", "SPRING_", "S01_", "S04_", "JWT_", "REDIS_", "LOGIN_RATE_"))}
            proc = subprocess.Popen([java, "-jar", str(jars[index]), *[f"--{key}={value}" for key, value in {**common, **options}.items()]],
                                    cwd=temp, env=environment, stdout=stream, stderr=subprocess.STDOUT)
            processes.append(proc)
            base = [s01, s04, gateway][index]
            health_path = "/api/v1/users/me/access-context" if index == 2 else "/actuator/health"
            ready_status = 401 if index == 2 else 200
            for _ in range(180):
                if proc.poll() is not None:
                    raise RuntimeError(f"Service {index} exited; inspect {logfile}")
                try:
                    if request(base, health_path)[0] == ready_status:
                        return proc
                except (URLError, TimeoutError, ConnectionError):
                    pass
                time.sleep(0.5)
            raise RuntimeError(f"Service {index} startup timed out; inspect {logfile}")

        s01_process = start(0, {"server.port": s01_port, "DB_SCHEMA": "identity", "DB_USERNAME": "ld_s01_app",
                               "DB_PASSWORD": "test_app_password", "FLYWAY_USERNAME": "postgres",
                               "FLYWAY_PASSWORD": pg_password, "JWT_ISSUER": "urn:livingdocs:s01", "JWT_AUDIENCE": "livingdocs-api",
                               "JWT_PRIVATE_KEY_PATH": private, "JWT_PUBLIC_KEY_PATH": public,
                               "LOGIN_RATE_LIMIT_PEPPER_PATH": pepper, "REDIS_HOST": "127.0.0.1", "REDIS_PORT": redis_port,
                               "livingdocs.security.workloads.registry-path": registry,
                               "livingdocs.security.workloads.allow-insecure-transport": "true"})
        start(1, {"server.port": s04_port, "DB_SCHEMA": "document", "DB_USERNAME": "ld_s04_app", "DB_PASSWORD": "test-only",
                  "FLYWAY_USERNAME": "ld_s04_migrator", "FLYWAY_PASSWORD": "test-only",
                  "S01_ISSUER_URI": "urn:livingdocs:s01", "S01_JWK_SET_URI": s01 + "/.well-known/jwks.json",
                  "S04_TOKEN_AUDIENCE": "livingdocs-api", "S01_SUBMIT_ACCESS_CONTEXT_URI": s01 + "/api/v1/users/me/access-context",
                  "S04_SUBMIT_ALLOW_INSECURE_HTTP": "true",
                  "S01_REVIEW_ACCESS_CONTEXT_URI": s01 + "/api/v1/users/me/access-context",
                  "S01_AUTHORIZATION_SERVICE_TOKEN_URI": s01 + "/internal/v1/auth/service-tokens",
                  "S01_AUTHORIZATION_EVALUATION_URI": s01 + "/internal/v1/authorization/evaluate",
                  "S04_AUTHORIZATION_WORKLOAD_SECRET_PATH": workload_secret,
                  "S04_REVIEW_ALLOW_INSECURE_HTTP": "true", "S04_AUTHORIZATION_ALLOW_INSECURE_HTTP": "true"})
        check("S04 Flyway and CI migration/RLS counts agree", sql("SELECT (SELECT count(*) FROM document.flyway_schema_history WHERE success) || ':' || (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='document' AND c.relkind='r' AND c.relrowsecurity);") == "4:13")
        if args.gateway_newman:
            start(2, {"server.port": gateway_port, "management.server.port": gateway_management_port,
                      "spring.profiles.active": "", "JWT_ISSUER_URI": "urn:livingdocs:s01",
                      "JWT_JWK_SET_URI": s01 + "/.well-known/jwks.json", "JWT_AUDIENCE": "livingdocs-api",
                      "S01_SERVICE_URI": s01, "S04_SERVICE_URI": s04})

        email, password = f"developer-{run_id}@example.test", secrets.token_urlsafe(24) + "aA1!"
        result = request(s01, "/api/v1/auth/register", payload={"email": email, "password": password})
        check("S01 real HTTP registration", result[0] == 201)
        actor = result[2]["data"]["identityId"]
        result = request(s01, "/api/v1/auth/login", payload={"email": email, "password": password})
        check("S01 real HTTP login with Redis limiter", result[0] == 200)
        token = result[2]["data"]["accessToken"]
        encoded = token.split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))
        check("S01 JWT carries Identity, no tenant/workspace authority", claims["sub"] == actor and
              not {"tenant_id", "workspace_grants", "tenant_active"}.intersection(claims))
        tenant, workspace, member, workspace_member, repository = [str(uuid4()) for _ in range(5)]
        registry.write_text(json.dumps([{"id": "s04", "secretHash": workload_hash.read_text(encoding="utf-8"),
                                        "audiences": ["livingdocs-s01-internal"], "operations": ["authorization:evaluate"],
                                        "tenantIds": [tenant]}]), encoding="utf-8")

        def fixture(tenant_id=tenant, workspace_id=workspace, repo=None):
            collection, document, version, evidence = [str(uuid4()) for _ in range(4)]
            repo_literal = "NULL" if repo is None else f"'{repo}'"
            sql(f"""INSERT INTO document.document_collections(collection_id,tenant_id,workspace_id,name) VALUES ('{collection}','{tenant_id}','{workspace_id}','API-113 isolated test');
INSERT INTO document.documents(document_id,tenant_id,collection_id,repository_connection_id,title,ownership_ref) VALUES ('{document}','{tenant_id}','{collection}',{repo_literal},'Test','test');
INSERT INTO document.document_versions(document_version_id,tenant_id,document_id,version_no,state,review_stage,source_sha,creator_actor_ref,source_target_kind,source_target_ref,source_config_revision,source_captured_at) VALUES ('{version}','{tenant_id}','{document}',1,'DRAFT',NULL,'{'a' * 40}','test','TRACKED_BRANCH','refs/heads/main','1',now());
INSERT INTO document.document_evidence_refs(document_evidence_id,tenant_id,document_version_id,evidence_type,source_ref) VALUES ('{evidence}','{tenant_id}','{version}','SOURCE','test://isolated');""")
            return version, evidence

        first, evidence = fixture(repo=repository)
        payload = {}

        def submit(version, body=payload, jwt=token, key="integration"):
            return request(s04, f"/api/v1/document-versions/{version}/submit", jwt, body,
                           {"If-Match": f'"{version}:revision-0"', "Idempotency-Key": key})

        def expected(name, result, status, code):
            check(f"{name} (HTTP {result[0]}, {result[2].get('code')})", result[0] == status and result[2].get("code") == code)

        expected("Unprovisioned Identity denied", submit(first), 403, "FORBIDDEN_SCOPE")
        sql(f"""INSERT INTO identity.tenants(tenant_id,name) VALUES ('{tenant}','API-113 isolated test');
INSERT INTO identity.tenant_memberships(membership_id,tenant_id,identity_id) VALUES ('{member}','{tenant}','{actor}');
INSERT INTO identity.workspaces(workspace_id,tenant_id,name) VALUES ('{workspace}','{tenant}','Developer workspace');
INSERT INTO identity.workspace_memberships(workspace_membership_id,tenant_id,workspace_id,tenant_membership_id,identity_id) VALUES ('{workspace_member}','{tenant}','{workspace}','{member}','{actor}');
INSERT INTO identity.workspace_role_assignments(tenant_id,workspace_id,workspace_membership_id,role_code) VALUES ('{tenant}','{workspace}','{workspace_member}','DEVELOPER');
INSERT INTO identity.repository_grants(tenant_id,workspace_id,workspace_membership_id,repository_connection_id,provenance,operator_identity_id) VALUES ('{tenant}','{workspace}','{workspace_member}','{repository}','Isolated pending test fixture','{actor}');""")
        context = request(s01, "/api/v1/users/me/access-context", token)[2]["data"]
        check("API-038 returns real DB Developer permission, excludes PENDING repository", context["tenantId"] == tenant and
              "DOCUMENT_AUTHOR" in context["workspaceGrants"][0]["permissions"] and not context["workspaceGrants"][0]["repositoryIds"])
        expected("PENDING grant denies fresh Submit", submit(first), 403, "FORBIDDEN_SCOPE")
        sql(f"UPDATE identity.repository_grants SET verification_status='VERIFIED', verification_source='OPERATOR_ATTESTED', verified_at=now(), provenance='CONTROLLED SQL TEST FIXTURE: no operator or S02 binding workflow executed' WHERE repository_connection_id='{repository}';")
        original = submit(first)
        expected("API-113 Submit with real S01 JWT/context", original, 200, "DOCUMENT_SUBMITTED")
        check("Submit advances Staff Review with ETag", original[2]["data"]["reviewStage"] == "STAFF_REVIEW" and
              original[2]["data"]["revision"] == 1 and any(k.lower() == "etag" for k in original[1]))
        replay = submit(first)
        check("Exact replay preserves response", replay[0] == 200 and replay[2] == original[2])
        blocked, _ = fixture(repo=repository)
        foreign, _ = fixture(str(uuid4()), str(uuid4()))
        wrong_workspace, _ = fixture(tenant, str(uuid4()))
        pending_repository = str(uuid4())
        sql(f"INSERT INTO identity.repository_grants(tenant_id,workspace_id,workspace_membership_id,repository_connection_id,provenance,operator_identity_id) VALUES ('{tenant}','{workspace}','{workspace_member}','{pending_repository}','Isolated PENDING fixture','{actor}');")
        repo_version, _ = fixture(repo=pending_repository)
        free_version, _ = fixture()
        expected("Repository-free Submit fails closed under API-039 contract", submit(free_version), 503, "AUTHORIZATION_UNAVAILABLE")
        reason = {"note": "Denied scope"}
        expected("Cross-tenant version hidden by RLS", submit(foreign, reason), 404, "DOCUMENT_VERSION_NOT_FOUND")
        expected("Cross-workspace denied", submit(wrong_workspace, reason), 403, "FORBIDDEN_SCOPE")
        expected("PENDING repository grant cannot authorize Submit", submit(repo_version, reason), 403, "FORBIDDEN_SCOPE")
        expected("Invalid bearer denied locally", submit(blocked, reason, jwt="invalid"), 401, "UNAUTHENTICATED")
        if args.gateway_newman:
            # Keep the PENDING denial fixture. A separate repository is VERIFIED only by controlled test SQL.
            verified_repository = str(uuid4())
            sql(f"INSERT INTO identity.repository_grants(tenant_id,workspace_id,workspace_membership_id,repository_connection_id,provenance,operator_identity_id,verification_status,verification_source,verified_at) VALUES ('{tenant}','{workspace}','{workspace_member}','{verified_repository}','CONTROLLED SQL TEST FIXTURE: no operator or S02 binding workflow executed','{actor}','VERIFIED','OPERATOR_ATTESTED',now());")
            newman_version, newman_evidence = fixture(repo=verified_repository)
            missing_evidence, _ = fixture(repo=verified_repository)
            sql(f"DELETE FROM document.document_evidence_refs WHERE document_version_id='{missing_evidence}';")
            newman_variables = {"gatewayUrl": gateway, "email": email, "password": password, "accessToken": token,
                                "actorId": actor, "newmanVersionId": newman_version, "draftVersionId": blocked,
                                "noEvidenceVersionId": missing_evidence, "foreignVersionId": foreign,
                                "wrongWorkspaceVersionId": wrong_workspace, "pendingVersionId": repo_version,
                                "repositoryFreeVersionId": free_version, "newmanEvidenceId": newman_evidence,
                                "newmanRepositoryId": verified_repository}
            report["newman"].append(run_newman("01 Scoped submit", newman_variables, args.newman_cli, temp))
            check("Newman Gateway positive/replay/negative phase", True)
            check("Gateway Submit persists exactly one receipt and audit", sql(f"SELECT (SELECT count(*) FROM document.submit_requests WHERE document_version_id='{newman_version}') || ':' || (SELECT count(*) FROM document.document_audits WHERE document_version_id='{newman_version}');") == "1:1")
            check("Gateway denied Draft remains unchanged", sql(f"SELECT revision FROM document.document_versions WHERE document_version_id='{blocked}';") == "0")
            # Existing Staff Return is a dependency used only to prepare a real resubmit.
            sql(f"INSERT INTO identity.workspace_role_assignments(tenant_id,workspace_id,workspace_membership_id,role_code) VALUES ('{tenant}','{workspace}','{workspace_member}','STAFF');")
            report["newman"].append(run_newman("02 Return and resubmit", newman_variables, args.newman_cli, temp))
            check("Newman Gateway Return/resubmit preserves workflow and receipts", sql(f"SELECT state || ':' || review_stage || ':' || revision || ':' || (SELECT count(*) FROM document.submit_requests WHERE document_version_id='{newman_version}') || ':' || (SELECT count(*) FROM document.review_decisions WHERE document_version_id='{newman_version}') || ':' || (SELECT count(*) FROM document.document_audits WHERE document_version_id='{newman_version}') FROM document.document_versions WHERE document_version_id='{newman_version}';") == "IN_REVIEW:STAFF_REVIEW:3:2:1:3")
            check("Gateway Staff Return records real fresh authorization", sql(f"SELECT metadata->'authorization'->>'action' FROM document.document_audits WHERE document_version_id='{newman_version}' AND action='STAFF_REVIEW_RETURN_FOR_REVISION';") == "DOCUMENT_STAFF_REVIEW"
                  and bool(sql(f"SELECT metadata->'authorization'->>'decisionId' FROM document.document_audits WHERE document_version_id='{newman_version}' AND action='STAFF_REVIEW_RETURN_FOR_REVISION';")))
            check("Repository-free denial preserves Draft", sql(f"SELECT state || ':' || revision || ':' || (SELECT count(*) FROM document.document_audits WHERE document_version_id='{free_version}') FROM document.document_versions WHERE document_version_id='{free_version}';") == "DRAFT:0:0")
            original_secret = workload_secret.read_text(encoding="ascii")
            workload_secret.write_text("incorrect-test-workload-secret", encoding="ascii")
            report["newman"].append(run_newman("05 Review workload unavailable", newman_variables, args.newman_cli, temp))
            workload_secret.write_text(original_secret, encoding="ascii")
            check("Newman workload authentication failure denies new review and replay", sql(f"SELECT revision || ':' || (SELECT count(*) FROM document.review_decisions WHERE document_version_id='{newman_version}') || ':' || (SELECT count(*) FROM document.document_audits WHERE document_version_id='{newman_version}') FROM document.document_versions WHERE document_version_id='{newman_version}';") == "3:1:3")
            report["dependencies"] = [{"id": "Gateway LD-D11", "status": "BLOCKED", "scope": "Global Gateway security error body; anonymous collection check verifies 401/challenge only, not RFC 9457 conformance"},
                                      {"id": "S01 API-039", "status": "VERIFIED_FOR_API_113_AND_API_120", "scope": "Real Service JWT/fresh decision for Submit and repository-bound Staff Return; fresh API-039 is required for Submit with DOCUMENT_AUTHOR"},
                                      {"id": "Production repository grants", "status": "NOT_VERIFIED", "scope": "Controlled SQL fixture, not Request/Verify/Activate or trusted S02 binding"}]
        original_secret = workload_secret.read_text(encoding="ascii")
        workload_secret.write_text("invalid-test-workload-secret", encoding="ascii")
        expected("Bad workload denies new Submit", submit(blocked, reason), 503, "AUTHORIZATION_UNAVAILABLE")
        expected("Bad workload denies Submit replay", submit(first), 503, "AUTHORIZATION_UNAVAILABLE")
        workload_secret.write_text(original_secret, encoding="ascii")
        check("Submit audit records real DOCUMENT_AUTHOR fresh decision", sql(f"SELECT metadata->'authorization'->>'action' FROM document.document_audits WHERE document_version_id='{first}';") == "DOCUMENT_AUTHOR"
              and bool(sql(f"SELECT metadata->'authorization'->>'decisionId' FROM document.document_audits WHERE document_version_id='{first}';")))
        sql(f"UPDATE identity.workspace_role_assignments SET status='REVOKED', ended_at=now() WHERE workspace_membership_id='{workspace_member}';")
        expected("Same JWT denied after Developer role revoke", submit(blocked, reason), 403, "FORBIDDEN_SCOPE")
        expected("Exact replay denied after Developer role revoke", submit(first), 403, "FORBIDDEN_SCOPE")
        if args.gateway_newman:
            report["newman"].append(run_newman("03 Revoked role", newman_variables, args.newman_cli, temp))
            check("Newman Gateway revoked role denies mutation and replay", True)
        sql(f"UPDATE identity.workspace_role_assignments SET status='ACTIVE', ended_at=NULL WHERE workspace_membership_id='{workspace_member}';")
        sql(f"UPDATE identity.tenant_memberships SET status='REVOKED', ended_at=now() WHERE membership_id='{member}';")
        expected("Same JWT denied after tenant membership revoke", submit(blocked, reason), 403, "FORBIDDEN_SCOPE")
        s01_process.terminate()
        s01_process.wait(timeout=20)
        expected("S01 down fails closed", submit(blocked, reason), 503, "AUTHORIZATION_UNAVAILABLE")
        expected("S01 down also denies replay", submit(first), 503, "AUTHORIZATION_UNAVAILABLE")
        if args.gateway_newman:
            report["newman"].append(run_newman("04 S01 unavailable", newman_variables, args.newman_cli, temp))
            check("Newman Gateway S01 outage denies mutation and replay", True)
            check("Gateway replay never creates extra receipt/audit", sql(f"SELECT (SELECT count(*) FROM document.submit_requests WHERE document_version_id='{newman_version}') || ':' || (SELECT count(*) FROM document.document_audits WHERE document_version_id='{newman_version}');") == "2:3")
        check("Denied requests leave version/submission/audit unchanged", sql(f"SELECT revision || ':' || (SELECT count(*) FROM document.submit_requests WHERE document_version_id='{blocked}') || ':' || (SELECT count(*) FROM document.document_audits WHERE document_version_id='{blocked}') FROM document.document_versions WHERE document_version_id='{blocked}';") == "0:0:0")
        check("Replay writes exactly one submission and audit", sql(f"SELECT (SELECT count(*) FROM document.submit_requests WHERE document_version_id='{first}') || ':' || (SELECT count(*) FROM document.document_audits WHERE document_version_id='{first}');") == "1:1")
        check("Submission and audit record actual S01 Identity", sql(f"SELECT actor_identity_id FROM document.submit_requests WHERE document_version_id='{first}';") == actor
              and sql(f"SELECT actor_ref FROM document.document_audits WHERE document_version_id='{first}';") == actor)
        report["result"] = "PASS"
    except Exception as error:
        report["result"] = "FAIL"
        report["error"] = str(error)
        raise
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
        for stream in logs:
            stream.close()
        for name in reversed(containers):
            command("docker", "rm", "-f", name)
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Evidence: {args.output}", flush=True)
        temporary.cleanup()


if __name__ == "__main__":
    main()
