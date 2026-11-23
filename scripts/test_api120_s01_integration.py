"""Run real S01 login/API-038 -> S04 API-120 against disposable PostgreSQL/Redis.

Build both service jars first. Requires Java 21, Docker and Python 3.12; no Python
packages. Never reads .env or uses the developer database. Authority SQL below is
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
import socket
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
S01 = ROOT / "backend/identity-organization-service"
S04 = ROOT / "backend/document-governance-service"


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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=S04 / "target/api120-s01-integration.json")
    args = parser.parse_args()
    jars = [service / f"target/{service.name}-0.0.1-SNAPSHOT.jar" for service in (S01, S04)]
    if not all(path.is_file() for path in jars):
        raise SystemExit("Build both services with mvn -B -ntp package first.")
    report = {"preparedBy": "Codex", "startedAt": datetime.now(timezone.utc).isoformat(),
              "scope": "Real S01 Identity JWT + API-038; S04 API-120. Disposable authority fixtures; no S02/Gateway.",
              "artifactsSha256": {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                                  for path in [*jars, Path(__file__).resolve()]},
              "checks": []}
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
            ready = subprocess.run(["docker", "exec", pg, "pg_isready", "-U", "postgres", "-d", "livingdocs"], capture_output=True)
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
                               "LOGIN_RATE_LIMIT_PEPPER_PATH": pepper, "REDIS_HOST": "127.0.0.1", "REDIS_PORT": redis_port})
        start(1, {"server.port": s04_port, "DB_SCHEMA": "document", "DB_USERNAME": "ld_s04_app", "DB_PASSWORD": "test-only",
                  "FLYWAY_USERNAME": "ld_s04_migrator", "FLYWAY_PASSWORD": "test-only",
                  "S01_ISSUER_URI": "urn:livingdocs:s01", "S01_JWK_SET_URI": s01 + "/.well-known/jwks.json",
                  "S04_TOKEN_AUDIENCE": "livingdocs-api", "S01_REVIEW_ACCESS_CONTEXT_URI": s01 + "/api/v1/users/me/access-context",
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

        def fixture(tenant_id=tenant, workspace_id=workspace, repo=None):
            collection, document, version, evidence = [str(uuid4()) for _ in range(4)]
            repo_literal = "NULL" if repo is None else f"'{repo}'"
            sql(f"""INSERT INTO document.document_collections(collection_id,tenant_id,workspace_id,name) VALUES ('{collection}','{tenant_id}','{workspace_id}','API-120 isolated test');
INSERT INTO document.documents(document_id,tenant_id,collection_id,repository_connection_id,title,ownership_ref) VALUES ('{document}','{tenant_id}','{collection}',{repo_literal},'Test','test');
INSERT INTO document.document_versions(document_version_id,tenant_id,document_id,version_no,state,review_stage,source_sha,creator_actor_ref) VALUES ('{version}','{tenant_id}','{document}',1,'IN_REVIEW','STAFF_REVIEW','{'a' * 40}','test');
INSERT INTO document.document_evidence_refs(document_evidence_id,tenant_id,document_version_id,evidence_type,source_ref) VALUES ('{evidence}','{tenant_id}','{version}','SOURCE','test://isolated');""")
            return version, evidence

        first, evidence = fixture()
        payload = {"decision": "PASS", "evidenceRefs": [evidence]}

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
        original = review(first)
        expected("API-120 PASS with real S01 JWT/context", original, 200, "STAFF_REVIEW_RECORDED")
        check("PASS advances Manager Approval with ETag", original[2]["data"]["reviewStage"] == "MANAGER_APPROVAL" and
              original[2]["data"]["revision"] == 1 and any(k.lower() == "etag" for k in original[1]))
        replay = review(first)
        check("Exact replay preserves response", replay[0] == 200 and replay[2] == original[2])
        returned, _ = fixture()
        result = review(returned, {"decision": "RETURN_FOR_REVISION", "feedback": "Needs changes"})
        expected("API-120 RETURN with real S01 authority", result, 200, "STAFF_REVIEW_RECORDED")
        check("RETURN persists Draft", result[2]["data"]["state"] == "DRAFT")
        blocked, _ = fixture()
        foreign, _ = fixture(str(uuid4()), str(uuid4()))
        wrong_workspace, _ = fixture(tenant, str(uuid4()))
        repo_version, _ = fixture(repo=repository)
        reason = {"decision": "RETURN_FOR_REVISION", "feedback": "Denied scope"}
        expected("Cross-tenant version hidden by RLS", review(foreign, reason), 404, "DOCUMENT_VERSION_NOT_FOUND")
        expected("Cross-workspace denied", review(wrong_workspace, reason), 403, "REVIEW_FORBIDDEN")
        expected("PENDING repository grant cannot authorize RETURN", review(repo_version, reason), 403, "REVIEW_FORBIDDEN")
        expected("Invalid bearer denied locally", review(blocked, reason, jwt="invalid"), 401, "UNAUTHENTICATED")
        sql(f"UPDATE identity.workspace_role_assignments SET status='REVOKED', ended_at=now() WHERE workspace_membership_id='{workspace_member}';")
        expected("Same JWT denied after Staff role revoke", review(blocked, reason), 403, "REVIEW_FORBIDDEN")
        expected("Exact replay denied after Staff role revoke", review(first), 403, "REVIEW_FORBIDDEN")
        sql(f"UPDATE identity.tenant_memberships SET status='REVOKED', ended_at=now() WHERE membership_id='{member}';")
        expected("Same JWT denied after tenant membership revoke", review(blocked, reason), 403, "REVIEW_FORBIDDEN")
        s01_process.terminate()
        s01_process.wait(timeout=20)
        expected("S01 down fails closed", review(blocked, reason), 503, "AUTHORIZATION_UNAVAILABLE")
        expected("S01 down also denies replay", review(first), 503, "AUTHORIZATION_UNAVAILABLE")
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
