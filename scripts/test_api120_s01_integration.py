"""Run real S01 login/API-038/Service JWT/API-039 -> S04 API-120 on disposable databases.

Build both service jars first. Requires Java 21, Docker and Python 3.12; no Python
packages. Never reads .env or uses the developer database. Authority SQL below is
an isolated test fixture, not a supported operator provisioning workflow.
--newman also runs the LIV-118 collection against real S01/S04; requires Node and Newman 6.2.3.
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
COLLECTION = ROOT / "tests/postman/liv118-review.postman_collection.json"


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
    """Persist only sanitized statistics; ephemeral environment and raw responses stay temporary."""
    collection = json.loads(COLLECTION.read_text(encoding="utf-8"))
    expected = len(next(group for group in collection["item"] if group["name"] == folder)["item"])
    environment, raw_report = temporary / "newman-environment.json", temporary / "newman-report.json"
    environment.write_text(json.dumps({"name": "disposable-liv118", "values": [
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
        failures = [{"request": f.get("source", {}).get("name"), "errorType": f.get("error", {}).get("name")}
                    for f in raw.get("failures", [])]
        raise AssertionError("Newman phase failed: " + json.dumps({"summary": summary, "failures": failures}))
    raw_report.unlink()
    environment.unlink()
    summary["result"] = "PASS"
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=S04 / "target/api120-s01-integration.json")
    parser.add_argument("--newman", action="store_true", help="Run all LIV-118 collection phases on disposable fixtures")
    parser.add_argument("--newman-cli", type=Path, help="Path to Newman 6.2.3 bin/newman.js")
    args = parser.parse_args()
    jars = [service / f"target/{service.name}-0.0.1-SNAPSHOT.jar" for service in (S01, S04)]
    if not all(path.is_file() for path in jars):
        raise SystemExit("Build both services with mvn -B -ntp package first.")
    if args.newman and (args.newman_cli is None or not args.newman_cli.is_file() or shutil.which("node") is None):
        raise SystemExit("--newman requires Node and --newman-cli pointing to Newman 6.2.3 bin/newman.js")
    if args.newman:
        package = json.loads((args.newman_cli.resolve().parents[1] / "package.json").read_text(encoding="utf-8"))
        if package.get("name") != "newman" or package.get("version") != "6.2.3":
            raise SystemExit("This runner requires Newman 6.2.3")
    report = {"preparedBy": "Codex", "startedAt": datetime.now(timezone.utc).isoformat(),
              "scope": "Real S01 Identity JWT + API-038 + Service JWT + API-039; S04 API-120. No S02/Gateway.",
              "fixtureMode": "CONTROLLED SQL authority/VERIFIED-grant fixture, not production Grant lifecycle or S02 binding proof",
              "artifactsSha256": {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                                  for path in [*jars, Path(__file__).resolve()]},
              "checks": []}
    if args.newman:
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

    temporary = tempfile.TemporaryDirectory(prefix="ld-api120-")
    pg_password = secrets.token_hex(24)
    try:
        java = java_executable()
        temp = Path(temporary.name)
        for image, port, extra in (("postgres:17", 5432, ["-e", "POSTGRES_DB=livingdocs", "-e", "POSTGRES_PASSWORD=" + pg_password]),
                                   ("redis:8.2-alpine", 6379, [])):
            name = "ld-api120-" + run_id + "-" + str(port)
            command("docker", "run", "--rm", "-d", "--name", name, "-p", f"127.0.0.1::{port}", *extra, image)
            containers.append(name)
        pg, redis = containers
        pg_port = int(command("docker", "port", pg, "5432/tcp").rsplit(":", 1)[1])
        redis_port = int(command("docker", "port", redis, "6379/tcp").rsplit(":", 1)[1])
        for _ in range(120):
            # The image's initialization server accepts Unix sockets before restarting.
            # TCP readiness waits for the final server, avoiding a false ready/shutdown race.
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
        # Reuse the BCrypt implementation packaged in the S01 jar; do not install Python crypto packages.
        with zipfile.ZipFile(jars[0]) as archive:
            entry = next(name for name in archive.namelist() if name.startswith("BOOT-INF/lib/spring-security-crypto-"))
            crypto = temp / Path(entry).name
            crypto.write_bytes(archive.read(entry))
        hasher = temp / "HashWorkload.java"
        hasher.write_text('''import java.nio.file.*; import org.springframework.security.crypto.bcrypt.BCrypt;
class HashWorkload { public static void main(String[] a) throws Exception {
Files.writeString(Path.of(a[1]), BCrypt.hashpw(Files.readString(Path.of(a[0])), BCrypt.gensalt())); }}''', encoding="utf-8")
        command(java, "--class-path", str(crypto), str(hasher), str(workload_secret), str(workload_hash))
        s01_port, s04_port = free_port(), free_port()
        s01, s04 = f"http://127.0.0.1:{s01_port}", f"http://127.0.0.1:{s04_port}"
        common = {"spring.profiles.active": "db", "server.address": "127.0.0.1",
                  "DB_URL": f"jdbc:postgresql://127.0.0.1:{pg_port}/livingdocs",
                  "spring.config.import": "", "spring.jpa.hibernate.ddl-auto": "validate"}

        def start(index, options):
            logfile = args.output.parent / f"api120-s01-integration-{index}.log"
            stream = logfile.open("w", encoding="utf-8")
            logs.append(stream)
            environment = {key: value for key, value in os.environ.items()
                           if not key.startswith(("DB_", "FLYWAY_", "SPRING_", "S01_", "S04_", "JWT_", "REDIS_", "LOGIN_RATE_"))}
            proc = subprocess.Popen([java, "-jar", str(jars[index]), *[f"--{key}={value}" for key, value in {**common, **options}.items()]],
                                    cwd=temp, env=environment, stdout=stream, stderr=subprocess.STDOUT)
            processes.append(proc)
            base = s01 if index == 0 else s04
            for _ in range(180):
                if proc.poll() is not None:
                    raise RuntimeError(f"Service {index} exited; inspect {logfile}")
                try:
                    if request(base, "/actuator/health")[0] == 200:
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
                  "S04_TOKEN_AUDIENCE": "livingdocs-api", "S01_REVIEW_ACCESS_CONTEXT_URI": s01 + "/api/v1/users/me/access-context",
                  "S01_REVIEW_SERVICE_TOKEN_URI": s01 + "/internal/v1/auth/service-tokens",
                  "S01_REVIEW_AUTHORIZATION_URI": s01 + "/internal/v1/authorization/evaluate",
                  "S04_REVIEW_WORKLOAD_SECRET_PATH": workload_secret,
                  "S04_REVIEW_ALLOW_INSECURE_HTTP": "true"})

        email, password = f"staff-{run_id}@example.test", secrets.token_urlsafe(24) + "aA1!"
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
            sql(f"""INSERT INTO document.document_collections(collection_id,tenant_id,workspace_id,name) VALUES ('{collection}','{tenant_id}','{workspace_id}','API-120 isolated test');
INSERT INTO document.documents(document_id,tenant_id,collection_id,repository_connection_id,title,ownership_ref) VALUES ('{document}','{tenant_id}','{collection}',{repo_literal},'Test','test');
INSERT INTO document.document_versions(document_version_id,tenant_id,document_id,version_no,state,review_stage,source_sha,creator_actor_ref) VALUES ('{version}','{tenant_id}','{document}',1,'IN_REVIEW','STAFF_REVIEW','{'a' * 40}','test');
INSERT INTO document.document_evidence_refs(document_evidence_id,tenant_id,document_version_id,evidence_type,source_ref) VALUES ('{evidence}','{tenant_id}','{version}','SOURCE','test://isolated');""")
            return version, evidence

        first, evidence = fixture(repo=repository)
        payload = {"decision": "RETURN_FOR_REVISION", "feedback": "Real fresh authority integration", "evidenceRefs": [evidence]}

        def review(version, body=payload, jwt=token, key="integration"):
            return request(s04, f"/api/v1/document-versions/{version}/staff-decisions", jwt, body,
                           {"If-Match": f'"{version}:revision-0"', "Idempotency-Key": key})

        def expected(name, result, status, code):
            check(f"{name} (HTTP {result[0]}, {result[2].get('code')})", result[0] == status and result[2].get("code") == code)

        expected("Unprovisioned Identity denied", review(first), 403, "REVIEW_FORBIDDEN")
        sql(f"""INSERT INTO identity.tenants(tenant_id,name) VALUES ('{tenant}','API-120 isolated test');
INSERT INTO identity.tenant_memberships(membership_id,tenant_id,identity_id) VALUES ('{member}','{tenant}','{actor}');
INSERT INTO identity.workspaces(workspace_id,tenant_id,name) VALUES ('{workspace}','{tenant}','Staff workspace');
INSERT INTO identity.workspace_memberships(workspace_membership_id,tenant_id,workspace_id,tenant_membership_id,identity_id) VALUES ('{workspace_member}','{tenant}','{workspace}','{member}','{actor}');
INSERT INTO identity.workspace_role_assignments(tenant_id,workspace_id,workspace_membership_id,role_code) VALUES ('{tenant}','{workspace}','{workspace_member}','STAFF');
INSERT INTO identity.repository_grants(tenant_id,workspace_id,workspace_membership_id,repository_connection_id,provenance,operator_identity_id) VALUES ('{tenant}','{workspace}','{workspace_member}','{repository}','Isolated pending test fixture','{actor}');""")
        context = request(s01, "/api/v1/users/me/access-context", token)[2]["data"]
        check("API-038 returns real DB Staff permission, excludes PENDING repository", context["tenantId"] == tenant and
              "DOCUMENT_STAFF_REVIEW" in context["workspaceGrants"][0]["permissions"] and not context["workspaceGrants"][0]["repositoryIds"])
        expected("PENDING repository grant cannot authorize RETURN", review(first), 403, "REVIEW_FORBIDDEN")
        # Explicit controlled-test fixture only; does NOT prove S01/S02 trusted Grant lifecycle.
        sql(f"UPDATE identity.repository_grants SET verification_status='VERIFIED', verification_source='OPERATOR_ATTESTED', verified_at=now(), provenance='CONTROLLED SQL TEST FIXTURE: no operator or S02 binding workflow executed' WHERE repository_connection_id='{repository}';")
        original = review(first)
        expected("API-120 RETURN with real S01 JWT, Service JWT and fresh API-039", original, 200, "STAFF_REVIEW_RECORDED")
        check("RETURN persists Draft with ETag", original[2]["data"]["state"] == "DRAFT" and
              original[2]["data"]["revision"] == 1 and any(k.lower() == "etag" for k in original[1]))
        check("Audit records real S01 fresh decision", sql(f"SELECT metadata->'authorization'->>'action' FROM document.document_audits WHERE document_version_id='{first}';") == "DOCUMENT_STAFF_REVIEW"
              and bool(sql(f"SELECT metadata->'authorization'->>'decisionId' FROM document.document_audits WHERE document_version_id='{first}';")))
        replay = review(first)
        check("Exact replay preserves response", replay[0] == 200 and replay[2] == original[2])
        source_bound, source_evidence = fixture(repo=repository)
        expected("Fresh ALLOW cannot bypass missing S02 source adapter", review(source_bound, {"decision": "PASS", "evidenceRefs": [source_evidence]}), 503, "SOURCE_VALIDATION_UNAVAILABLE")
        check("Missing S02 leaves bound version and audit unchanged", sql(f"SELECT revision || ':' || (SELECT count(*) FROM document.document_audits WHERE document_version_id='{source_bound}') FROM document.document_versions WHERE document_version_id='{source_bound}';") == "0:0")
        free, _ = fixture()
        expected("Repository-free RETURN fails closed under current API-039 contract", review(free), 503, "AUTHORIZATION_UNAVAILABLE")
        blocked, _ = fixture(repo=repository)
        foreign, _ = fixture(str(uuid4()), str(uuid4()))
        wrong_workspace, _ = fixture(tenant, str(uuid4()))
        repo_version, _ = fixture(repo=str(uuid4()))
        reason = {"decision": "RETURN_FOR_REVISION", "feedback": "Denied scope"}
        expected("Cross-tenant version hidden by RLS", review(foreign, reason), 404, "DOCUMENT_VERSION_NOT_FOUND")
        expected("Cross-workspace denied", review(wrong_workspace, reason), 403, "REVIEW_FORBIDDEN")
        expected("Unknown repository grant cannot authorize RETURN", review(repo_version, reason), 403, "REVIEW_FORBIDDEN")
        expected("Invalid bearer denied locally", review(blocked, reason, jwt="invalid"), 401, "UNAUTHENTICATED")
        if args.newman:
            nm_version, nm_evidence = fixture(repo=repository)
            nm_blocked, _ = fixture(repo=repository)
            no_sha, no_sha_evidence = fixture(repo=repository)
            sql(f"UPDATE document.document_versions SET source_sha=NULL WHERE document_version_id='{no_sha}';")
            pending_repository = str(uuid4())
            sql(f"INSERT INTO identity.repository_grants(tenant_id,workspace_id,workspace_membership_id,repository_connection_id,provenance,operator_identity_id) VALUES ('{tenant}','{workspace}','{workspace_member}','{pending_repository}','Isolated PENDING test fixture','{actor}');")
            nm_pending, _ = fixture(repo=pending_repository)
            newman_variables = {"s01Url": s01, "s04Url": s04, "email": email, "password": password,
                                "accessToken": token, "actorId": actor, "repositoryId": repository,
                                "reviewVersionId": nm_version, "evidenceId": nm_evidence,
                                "blockedVersionId": nm_blocked, "sourceVersionId": source_bound,
                                "sourceEvidenceId": source_evidence, "noShaVersionId": no_sha,
                                "noShaEvidenceId": no_sha_evidence, "freeVersionId": free,
                                "foreignVersionId": foreign, "wrongWorkspaceVersionId": wrong_workspace,
                                "unknownRepoVersionId": repo_version, "pendingVersionId": nm_pending,
                                "foreignEvidenceId": str(uuid4())}

            def newman_unchanged():
                rows = sql(f"SELECT state || ':' || revision || ':' || (SELECT count(*) FROM document.review_decisions WHERE document_version_id='{nm_version}') || ':' || (SELECT count(*) FROM document.document_audits WHERE document_version_id='{nm_version}') FROM document.document_versions WHERE document_version_id='{nm_version}';")
                untouched = sql(f"SELECT sum(revision) || ':' || (SELECT count(*) FROM document.review_decisions WHERE document_version_id IN ('{nm_blocked}','{source_bound}','{no_sha}','{free}','{foreign}','{wrong_workspace}','{repo_version}','{nm_pending}')) || ':' || (SELECT count(*) FROM document.document_audits WHERE document_version_id IN ('{nm_blocked}','{source_bound}','{no_sha}','{free}','{foreign}','{wrong_workspace}','{repo_version}','{nm_pending}')) FROM document.document_versions WHERE document_version_id IN ('{nm_blocked}','{source_bound}','{no_sha}','{free}','{foreign}','{wrong_workspace}','{repo_version}','{nm_pending}');")
                return rows == "DRAFT:1:1:1" and untouched == "0:0:0"

            report["newman"].append(run_newman("01 Staff review", newman_variables, args.newman_cli, temp))
            check("LIV-118 Newman validates RETURN/replay, headers, evidence, scope and source failures", newman_unchanged())
            check("LIV-118 Newman RETURN audit binds real actor and fresh decision", sql(f"SELECT actor_ref || ':' || (metadata->'authorization'->>'action') FROM document.document_audits WHERE document_version_id='{nm_version}';") == actor + ":DOCUMENT_STAFF_REVIEW"
                  and bool(sql(f"SELECT metadata->'authorization'->>'decisionId' FROM document.document_audits WHERE document_version_id='{nm_version}';")))
        original_secret = workload_secret.read_text(encoding="ascii")
        workload_secret.write_text("incorrect-workload-credential", encoding="ascii")
        expected("Invalid S04 workload credential fails closed", review(blocked, reason), 503, "AUTHORIZATION_UNAVAILABLE")
        if args.newman:
            report["newman"].append(run_newman("02 Workload unavailable", newman_variables, args.newman_cli, temp))
            check("LIV-118 Newman bad workload denies new request and replay without writes", newman_unchanged())
        workload_secret.write_text(original_secret, encoding="ascii")
        sql(f"UPDATE identity.workspace_role_assignments SET status='REVOKED', ended_at=now() WHERE workspace_membership_id='{workspace_member}';")
        expected("Same JWT denied after Staff role revoke", review(blocked, reason), 403, "REVIEW_FORBIDDEN")
        expected("Exact replay denied after Staff role revoke", review(first), 403, "REVIEW_FORBIDDEN")
        if args.newman:
            report["newman"].append(run_newman("03 Revoked Staff", newman_variables, args.newman_cli, temp))
            check("LIV-118 Newman revoked Staff denies new request and replay without writes", newman_unchanged())
        sql(f"UPDATE identity.workspace_role_assignments SET status='ACTIVE', ended_at=NULL WHERE workspace_membership_id='{workspace_member}';")
        sql(f"UPDATE identity.tenant_memberships SET status='REVOKED', ended_at=now() WHERE membership_id='{member}';")
        expected("Same JWT denied after tenant membership revoke", review(blocked, reason), 403, "REVIEW_FORBIDDEN")
        if args.newman:
            report["newman"].append(run_newman("04 Revoked tenant", newman_variables, args.newman_cli, temp))
            check("LIV-118 Newman revoked tenant denies new request and replay without writes", newman_unchanged())
        s01_process.terminate()
        s01_process.wait(timeout=20)
        expected("S01 down fails closed", review(blocked, reason), 503, "AUTHORIZATION_UNAVAILABLE")
        expected("S01 down also denies replay", review(first), 503, "AUTHORIZATION_UNAVAILABLE")
        if args.newman:
            report["newman"].append(run_newman("05 S01 unavailable", newman_variables, args.newman_cli, temp))
            check("LIV-118 Newman S01 outage denies new request and replay without writes", newman_unchanged())
        check("Denied requests leave version/decision/audit unchanged", sql(f"SELECT revision || ':' || (SELECT count(*) FROM document.review_decisions WHERE document_version_id='{blocked}') || ':' || (SELECT count(*) FROM document.document_audits WHERE document_version_id='{blocked}') FROM document.document_versions WHERE document_version_id='{blocked}';") == "0:0:0")
        check("Replay writes exactly one decision and audit", sql(f"SELECT (SELECT count(*) FROM document.review_decisions WHERE document_version_id='{first}') || ':' || (SELECT count(*) FROM document.document_audits WHERE document_version_id='{first}');") == "1:1")
        check("Decision and audit record actual S01 Identity", sql(f"SELECT reviewer_identity_id FROM document.review_decisions WHERE document_version_id='{first}';") == actor
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
