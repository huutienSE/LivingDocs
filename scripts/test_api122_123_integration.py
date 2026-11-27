"""Run real S01 login/API-038 -> Gateway -> S04 API-122/123 against disposable PostgreSQL/Redis.

Build the S01, S04 and Gateway JARs first (mvn -B -ntp -DskipTests package).
Default infrastructure is Docker (same as scripts/test_api113_s01_integration.py).
Without Docker, pass --local-pg-bin (PostgreSQL bin directory) and --local-redis to start a
throwaway cluster and Redis in a temporary directory instead (Linux/macOS; as root it uses runuser).
--newman also needs Node and Newman 6.2.3 (--newman-cli path/to/newman/bin/newman.js).

Versions are placed at IN_REVIEW / MANAGER_APPROVAL by CONTROLLED SQL fixtures: a real Staff PASS
(API-120) needs the S02 source adapter, which does not exist yet. Identity/tenant/workspace/role rows
are also controlled SQL fixtures, not the production provisioning workflow. Never reads .env or a
developer database; credentials and raw Newman reports stay in the temporary directory.
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
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
S01 = ROOT / "backend/identity-organization-service"
S04 = ROOT / "backend/document-governance-service"
GATEWAY = ROOT / "infrastructure/api-gateway"
COLLECTION = ROOT / "tests/postman/liv98-approval-read.postman_collection.json"


def java_executable():
    # Launch the actual JVM (not a Windows launcher) so outage tests and cleanup work.
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
    environment.write_text(json.dumps({"name": "disposable-liv98", "values": [
        {"key": key, "value": value, "enabled": True, "type": "secret"}
        for key, value in variables.items()]}), encoding="utf-8")
    environment.chmod(0o600)
    result = subprocess.run([shutil.which("node"), str(cli), "run", str(COLLECTION),
                             "--folder", folder, "--environment", str(environment),
                             "--export-environment", str(environment),
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
        # Report only request names and assertion names, never bodies/headers that may contain a token.
        failures = [{"request": f.get("source", {}).get("name"), "assertion": f.get("error", {}).get("test"),
                     "errorType": f.get("error", {}).get("name")} for f in raw.get("failures", [])]
        raise AssertionError("Newman phase failed: " + json.dumps({"summary": summary, "failures": failures}))
    # Tokens set by the login requests are carried to the next phase through the exported environment.
    exported = {v["key"]: v["value"] for v in json.loads(environment.read_text(encoding="utf-8"))["values"]}
    variables.update({k: exported[k] for k in ("managerToken", "staffToken") if k in exported})
    raw_report.unlink()
    environment.unlink()
    summary["result"] = "PASS"
    return summary


class Infrastructure:
    """Disposable PostgreSQL + Redis, either Docker containers or local throwaway processes."""

    def __init__(self, args, run_id, temp, pg_password):
        self.args, self.run_id, self.temp, self.pg_password = args, run_id, temp, pg_password
        self.containers, self.processes = [], []
        self.pg_port = self.redis_port = None
        self.local = args.local_pg_bin is not None

    def start(self):
        if self.local:
            self._start_local()
        else:
            self._start_docker()

    def _start_docker(self):
        for image, port, extra in (("postgres:17", 5432, ["-e", "POSTGRES_DB=livingdocs", "-e", "POSTGRES_PASSWORD=" + self.pg_password]),
                                   ("redis:8.2-alpine", 6379, [])):
            name = "ld-api122-" + self.run_id + "-" + str(port)
            command("docker", "run", "--rm", "-d", "--name", name, "-p", f"127.0.0.1::{port}", *extra, image)
            self.containers.append(name)
        pg, redis = self.containers
        self.pg_port = int(command("docker", "port", pg, "5432/tcp").rsplit(":", 1)[1])
        self.redis_port = int(command("docker", "port", redis, "6379/tcp").rsplit(":", 1)[1])
        for _ in range(120):
            if subprocess.run(["docker", "exec", pg, "pg_isready", "-h", "127.0.0.1", "-U", "postgres", "-d", "livingdocs"],
                              capture_output=True).returncode == 0:
                return
            time.sleep(0.5)
        raise RuntimeError("Disposable PostgreSQL did not become ready")

    def _as_postgres(self, *argv):
        # initdb/postgres refuse to run as root; switch to the 'postgres' OS user when needed.
        if hasattr(os, "geteuid") and os.geteuid() == 0:
            return ["runuser", "-u", "postgres", "--", *argv]
        return list(argv)

    def _start_local(self):
        pg_bin = Path(self.args.local_pg_bin)
        data = self.temp / "pgdata"
        if hasattr(os, "geteuid") and os.geteuid() == 0:
            shutil.chown(self.temp, user="postgres")
        password_file = self.temp / "pgpass"
        password_file.write_text(self.pg_password, encoding="ascii")
        if hasattr(os, "geteuid") and os.geteuid() == 0:
            shutil.chown(password_file, user="postgres")
        command(*self._as_postgres(str(pg_bin / "initdb"), "-D", str(data), "-U", "postgres", "-A", "scram-sha-256",
                                   "--pwfile", str(password_file), "-E", "UTF8"))
        password_file.unlink()
        self.pg_port = free_port()
        log = (self.temp / "postgres.log").open("w")
        proc = subprocess.Popen(self._as_postgres(str(pg_bin / "postgres"), "-D", str(data), "-p", str(self.pg_port),
                                                  "-k", str(self.temp), "-c", "listen_addresses=127.0.0.1"),
                                stdout=log, stderr=subprocess.STDOUT)
        self.processes.append(proc)
        for _ in range(120):
            if subprocess.run([str(pg_bin / "pg_isready"), "-h", "127.0.0.1", "-p", str(self.pg_port)], capture_output=True).returncode == 0:
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("Local PostgreSQL did not become ready")
        self._psql("postgres", "CREATE DATABASE livingdocs;")
        self.redis_port = free_port()
        self.processes.append(subprocess.Popen([self.args.local_redis, "--port", str(self.redis_port), "--bind", "127.0.0.1",
                                                "--save", "", "--appendonly", "no", "--dir", str(self.temp)],
                                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        time.sleep(1)

    def _psql(self, database, text):
        env = dict(os.environ, PGPASSWORD=self.pg_password)
        result = subprocess.run([str(Path(self.args.local_pg_bin) / "psql"), "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
                                 "-h", "127.0.0.1", "-p", str(self.pg_port), "-U", "postgres", "-d", database],
                                input=text, text=True, encoding="utf-8", capture_output=True, env=env)
        if result.returncode:
            raise RuntimeError("psql failed: " + result.stderr[-2000:])
        return result.stdout.strip()

    def sql(self, text):
        if self.local:
            return self._psql("livingdocs", text)
        return command("docker", "exec", "-i", self.containers[0], "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
                       "-U", "postgres", "-d", "livingdocs", data=text)

    def stop(self):
        for proc in reversed(self.processes):
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    proc.kill()
        for name in reversed(self.containers):
            subprocess.run(["docker", "rm", "-f", name], capture_output=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--output", type=Path, default=S04 / "target/api122-123-integration.json")
    parser.add_argument("--newman", action="store_true", help="Also run the LIV-98 Newman phases through the real Gateway")
    parser.add_argument("--newman-cli", type=Path, help="Path to node_modules/newman/bin/newman.js (Newman 6.2.3)")
    parser.add_argument("--local-pg-bin", help="Use a throwaway local PostgreSQL cluster from this bin directory instead of Docker")
    parser.add_argument("--local-redis", default="redis-server", help="redis-server executable for --local-pg-bin mode")
    args = parser.parse_args()
    jars = [S01 / "target/identity-organization-service-0.0.1-SNAPSHOT.jar",
            S04 / "target/document-governance-service-0.0.1-SNAPSHOT.jar",
            GATEWAY / "target/api-gateway-0.0.1-SNAPSHOT.jar"]
    if not all(path.is_file() for path in jars):
        raise SystemExit("Build S01, S04 and Gateway with mvn -B -ntp -DskipTests package first.")
    if args.newman and (args.newman_cli is None or not args.newman_cli.is_file() or shutil.which("node") is None):
        raise SystemExit("--newman requires Node and --newman-cli pointing to Newman 6.2.3 bin/newman.js")
    report = {"startedAt": datetime.now(timezone.utc).isoformat(),
              "scope": "Real S01 Identity JWT + API-038, real Gateway, S04 API-122/123 on disposable PostgreSQL/Redis.",
              "fixtureMode": "CONTROLLED SQL fixtures for identity/tenant/role/VERIFIED-grant rows and for versions at "
                             "MANAGER_APPROVAL (real API-120 PASS needs the missing S02 adapter). Not production provisioning.",
              "infrastructure": "local" if args.local_pg_bin else "docker",
              "artifactsSha256": {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                                  for path in [*jars, Path(__file__).resolve(), COLLECTION]},
              "checks": [], "newman": []}
    processes, logs = [], []
    run_id = uuid4().hex[:12]
    args.output = args.output.resolve()
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        report["checks"].append({"name": name, "result": "PASS"})
        print("PASS " + name, flush=True)

    temporary = tempfile.TemporaryDirectory(prefix="ld-api122-")
    temp = Path(temporary.name)
    infra = Infrastructure(args, run_id, temp, secrets.token_hex(24))
    try:
        java = java_executable()
        infra.start()
        sql = infra.sql
        sql((S01 / "src/test/resources/s01-test-schema.sql").read_text(encoding="utf-8"))
        sql((S04 / "src/test/resources/db/bootstrap.sql").read_text(encoding="utf-8"))
        # Private key owned only by this isolated S01 process.
        source = temp / "TestKeys.java"
        source.write_text('''import java.nio.file.*; import java.security.*; import java.util.*;
class TestKeys { public static void main(String[] a) throws Exception {
var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair=generator.generateKeyPair();
Files.writeString(Path.of(a[0]), "-----BEGIN PRIVATE KEY-----\\n"+Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())+"\\n-----END PRIVATE KEY-----\\n");
Files.writeString(Path.of(a[1]), "-----BEGIN PUBLIC KEY-----\\n"+Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())+"\\n-----END PUBLIC KEY-----\\n"); }}''', encoding="utf-8")
        private, public, pepper = temp / "private.pem", temp / "public.pem", temp / "pepper"
        command(java, str(source), str(private), str(public))
        pepper.write_text(secrets.token_hex(32), encoding="ascii")
        registry = temp / "workloads.json"
        registry.write_text("[]", encoding="utf-8")
        s01_port, s04_port, gateway_port, gateway_management_port = [free_port() for _ in range(4)]
        s01, s04, gateway = (f"http://127.0.0.1:{p}" for p in (s01_port, s04_port, gateway_port))
        common = {"spring.profiles.active": "db", "server.address": "127.0.0.1",
                  "DB_URL": f"jdbc:postgresql://127.0.0.1:{infra.pg_port}/livingdocs",
                  "spring.config.import": "", "spring.jpa.hibernate.ddl-auto": "validate", "user.timezone": "UTC"}

        def start(index, options):
            logfile = args.output.parent / f"api122-123-integration-{index}.log"
            stream = logfile.open("w", encoding="utf-8")
            logs.append(stream)
            environment = {key: value for key, value in os.environ.items()
                           if not key.startswith(("DB_", "FLYWAY_", "SPRING_", "S01_", "S04_", "JWT_", "REDIS_", "LOGIN_RATE_"))}
            proc = subprocess.Popen([java, "-Duser.timezone=UTC", "-jar", str(jars[index]),
                                     *[f"--{key}={value}" for key, value in {**common, **options}.items()]],
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
                                "FLYWAY_PASSWORD": infra.pg_password, "JWT_ISSUER": "urn:livingdocs:s01", "JWT_AUDIENCE": "livingdocs-api",
                                "JWT_PRIVATE_KEY_PATH": private, "JWT_PUBLIC_KEY_PATH": public,
                                "LOGIN_RATE_LIMIT_PEPPER_PATH": pepper, "REDIS_HOST": "127.0.0.1", "REDIS_PORT": infra.redis_port,
                                "livingdocs.security.workloads.registry-path": registry,
                                "livingdocs.security.workloads.allow-insecure-transport": "true"})
        start(1, {"server.port": s04_port, "DB_SCHEMA": "document", "DB_USERNAME": "ld_s04_app", "DB_PASSWORD": "test-only",
                  "FLYWAY_USERNAME": "ld_s04_migrator", "FLYWAY_PASSWORD": "test-only",
                  "S01_ISSUER_URI": "urn:livingdocs:s01", "S01_JWK_SET_URI": s01 + "/.well-known/jwks.json",
                  "S04_TOKEN_AUDIENCE": "livingdocs-api",
                  # API-122/123 reuse the Staff Review API-038 endpoint unless S01_APPROVAL_ACCESS_CONTEXT_URI is set.
                  "S01_REVIEW_ACCESS_CONTEXT_URI": s01 + "/api/v1/users/me/access-context",
                  "S04_REVIEW_ALLOW_INSECURE_HTTP": "true",
                  "S01_SUBMIT_ACCESS_CONTEXT_URI": s01 + "/api/v1/users/me/access-context",
                  "S04_SUBMIT_ALLOW_INSECURE_HTTP": "true"})
        check("S04 Flyway and RLS counts agree with CI", sql("SELECT (SELECT count(*) FROM document.flyway_schema_history WHERE success) || ':' || (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='document' AND c.relkind='r' AND c.relrowsecurity);") == "4:13")
        start(2, {"server.port": gateway_port, "management.server.port": gateway_management_port,
                  "spring.profiles.active": "", "JWT_ISSUER_URI": "urn:livingdocs:s01",
                  "JWT_JWK_SET_URI": s01 + "/.well-known/jwks.json", "JWT_AUDIENCE": "livingdocs-api",
                  "S01_SERVICE_URI": s01, "S04_SERVICE_URI": s04})

        # Two real identities: a Manager and a Staff user (Staff must not see the Manager queue).
        users = {}
        for role in ("manager", "staff"):
            email, password = f"{role}-{run_id}@example.test", secrets.token_urlsafe(24) + "aA1!"
            result = request(s01, "/api/v1/auth/register", payload={"email": email, "password": password})
            check(f"S01 real HTTP registration ({role})", result[0] == 201)
            users[role] = {"email": email, "password": password, "id": result[2]["data"]["identityId"]}
        result = request(s01, "/api/v1/auth/login", payload={"email": users["manager"]["email"], "password": users["manager"]["password"]})
        check("S01 real HTTP login (manager)", result[0] == 200)
        token = result[2]["data"]["accessToken"]
        encoded = token.split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))
        check("S01 JWT carries Identity only, no tenant/workspace authority", claims["sub"] == users["manager"]["id"]
              and not {"tenant_id", "workspace_grants", "roles"}.intersection(claims))

        tenant, workspace, repository = str(uuid4()), str(uuid4()), str(uuid4())
        expected = ("Unprovisioned Identity has no Manager scope", request(s04, "/api/v1/approval-queue", token))
        check(f"{expected[0]} (HTTP {expected[1][0]})", expected[1][0] == 403 and expected[1][2]["code"] == "APPROVAL_FORBIDDEN")
        membership = {}
        sql(f"INSERT INTO identity.tenants(tenant_id,name) VALUES ('{tenant}','API-122 isolated test');"
            f"INSERT INTO identity.workspaces(workspace_id,tenant_id,name) VALUES ('{workspace}','{tenant}','Approval workspace');")
        for role, code in (("manager", "MANAGER"), ("staff", "STAFF")):
            member, workspace_member = str(uuid4()), str(uuid4())
            membership[role] = workspace_member
            actor = users[role]["id"]
            sql(f"""INSERT INTO identity.tenant_memberships(membership_id,tenant_id,identity_id) VALUES ('{member}','{tenant}','{actor}');
INSERT INTO identity.workspace_memberships(workspace_membership_id,tenant_id,workspace_id,tenant_membership_id,identity_id) VALUES ('{workspace_member}','{tenant}','{workspace}','{member}','{actor}');
INSERT INTO identity.workspace_role_assignments(tenant_id,workspace_id,workspace_membership_id,role_code) VALUES ('{tenant}','{workspace}','{workspace_member}','{code}');
INSERT INTO identity.repository_grants(tenant_id,workspace_id,workspace_membership_id,repository_connection_id,provenance,operator_identity_id,verification_status,verification_source,verified_at) VALUES ('{tenant}','{workspace}','{workspace_member}','{repository}','CONTROLLED SQL TEST FIXTURE: no operator or S02 binding workflow executed','{actor}','VERIFIED','OPERATOR_ATTESTED',now());""")
        context = request(s01, "/api/v1/users/me/access-context", token)[2]["data"]
        grant = next(g for g in context["workspaceGrants"] if g["workspaceId"] == workspace)
        check("API-038 returns real DB Manager permission and VERIFIED repository",
              context["tenantId"] == tenant and "DOCUMENT_FINAL_APPROVE" in grant["permissions"] and repository in grant["repositoryIds"])

        def collection(tenant_id, workspace_id):
            collection_id = str(uuid4())
            sql(f"INSERT INTO document.document_collections(collection_id,tenant_id,workspace_id,name) VALUES ('{collection_id}','{tenant_id}','{workspace_id}','API-122 isolated test');")
            return collection_id

        def version(collection_id, tenant_id, repo, title, state, stage, passed_minutes_ago):
            document, version_id = str(uuid4()), str(uuid4())
            repo_literal = "NULL" if repo is None else f"'{repo}'"
            stage_literal = "NULL" if stage is None else f"'{stage}'"
            sha = "NULL" if repo is None else f"'{'c' * 40}'"
            sql(f"""INSERT INTO document.documents(document_id,tenant_id,collection_id,repository_connection_id,title,ownership_ref) VALUES ('{document}','{tenant_id}','{collection_id}',{repo_literal},'{title}','test');
INSERT INTO document.document_versions(document_version_id,tenant_id,document_id,version_no,state,review_stage,source_sha,creator_actor_ref,revision) VALUES ('{version_id}','{tenant_id}','{document}',1,'{state}',{stage_literal},{sha},'controlled-fixture',2);""")
            if passed_minutes_ago is not None:
                sql(f"INSERT INTO document.review_decisions(tenant_id,document_version_id,reviewer_identity_id,decision,decided_at,round_no) VALUES ('{tenant_id}','{version_id}','{users['staff']['id']}','PASS',now() - interval '{passed_minutes_ago} minutes',1);")
            return version_id

        home = collection(tenant, workspace)
        older = version(home, tenant, repository, "Older", "IN_REVIEW", "MANAGER_APPROVAL", 60)
        newer = version(home, tenant, repository, "Newer", "IN_REVIEW", "MANAGER_APPROVAL", 10)
        repository_free = version(home, tenant, None, "Repository free", "IN_REVIEW", "MANAGER_APPROVAL", 5)
        staff_stage = version(home, tenant, repository, "Still at Staff", "IN_REVIEW", "STAFF_REVIEW", None)
        other_repo = version(home, tenant, str(uuid4()), "Ungranted repo", "IN_REVIEW", "MANAGER_APPROVAL", 30)
        other_workspace = version(collection(tenant, str(uuid4())), tenant, repository, "Other workspace", "IN_REVIEW", "MANAGER_APPROVAL", 30)
        foreign_tenant = str(uuid4())
        foreign = version(collection(foreign_tenant, workspace), foreign_tenant, repository, "Other tenant", "IN_REVIEW", "MANAGER_APPROVAL", 30)
        evidence = str(uuid4())
        sql(f"""INSERT INTO document.content_revisions(tenant_id,document_version_id,revision_no,content_ref,editor_identity_id) VALUES ('{tenant}','{older}',1,'test://v1','{users['staff']['id']}'),('{tenant}','{older}',2,'test://v2','{users['staff']['id']}');
INSERT INTO document.document_evidence_refs(document_evidence_id,tenant_id,document_version_id,evidence_type,source_ref) VALUES ('{evidence}','{tenant}','{older}','SOURCE','test://isolated');
INSERT INTO document.approval_decisions(tenant_id,document_version_id,approver_identity_id,decision,reason,round_no) VALUES ('{tenant}','{older}','{users['manager']['id']}','RETURN_TO_STAFF','Earlier round returned to Staff',1);""")

        def snapshot():
            return sql("SELECT string_agg(document_version_id::text || '=' || state || '/' || coalesce(review_stage,'-') || '/' || revision, ',' ORDER BY document_version_id) "
                       "|| '|' || (SELECT count(*) FROM document.review_decisions) || '|' || (SELECT count(*) FROM document.approval_decisions) "
                       "|| '|' || (SELECT count(*) FROM document.document_audits) FROM document.document_versions;")
        before = snapshot()

        queue = request(s04, "/api/v1/approval-queue", token)
        check("Direct S04 API-122 returns exactly the in-scope queue, oldest PASS first",
              queue[0] == 200 and [i["versionId"] for i in queue[2]["data"]["items"]] == [older, newer, repository_free])
        ctx = request(s04, f"/api/v1/document-versions/{older}/approval-context", token)
        check("Direct S04 API-123 returns context and version-bound ETag",
              ctx[0] == 200 and ctx[2]["data"]["awaitingManagerDecision"] is True
              and any(k.lower() == "etag" and v == f'"{older}:revision-2"' for k, v in ctx[1].items()))
        for name, vid, status, code in (("Cross-tenant version hidden by RLS", foreign, 404, "DOCUMENT_VERSION_NOT_FOUND"),
                                        ("Other workspace denied", other_workspace, 403, "APPROVAL_FORBIDDEN"),
                                        ("Ungranted repository denied", other_repo, 403, "APPROVAL_FORBIDDEN")):
            result = request(s04, f"/api/v1/document-versions/{vid}/approval-context", token)
            check(f"{name} (HTTP {result[0]}, {result[2].get('code')})", result[0] == status and result[2].get("code") == code)

        variables = {"gatewayUrl": gateway, "managerEmail": users["manager"]["email"], "managerPassword": users["manager"]["password"],
                     "staffEmail": users["staff"]["email"], "staffPassword": users["staff"]["password"],
                     "workspaceId": workspace, "repositoryId": repository, "olderVersionId": older, "newerVersionId": newer,
                     "repositoryFreeVersionId": repository_free, "staffStageVersionId": staff_stage, "otherRepoVersionId": other_repo,
                     "otherWorkspaceVersionId": other_workspace, "foreignVersionId": foreign, "unknownVersionId": str(uuid4()),
                     "evidenceId": evidence}
        if args.newman:
            report["newman"].append(run_newman("01 Manager reads", variables, args.newman_cli, temp))
        check("Reads leave versions, decisions and audit unchanged", snapshot() == before)

        sql(f"UPDATE identity.workspace_role_assignments SET status='REVOKED', ended_at=now() WHERE workspace_membership_id='{membership['manager']}';")
        revoked = request(s04, "/api/v1/approval-queue", token)
        check("Same JWT denied after MANAGER role revoke (no cached authority)", revoked[0] == 403 and revoked[2]["code"] == "APPROVAL_FORBIDDEN")
        if args.newman:
            report["newman"].append(run_newman("02 Revoked Manager", variables, args.newman_cli, temp))
        sql(f"UPDATE identity.workspace_role_assignments SET status='ACTIVE', ended_at=NULL WHERE workspace_membership_id='{membership['manager']}';")
        check("Restored MANAGER role is accepted again", request(s04, "/api/v1/approval-queue", token)[0] == 200)

        s01_process.terminate()
        s01_process.wait(timeout=20)
        down = request(s04, "/api/v1/approval-queue", token)
        check("S01 down fails closed (503 AUTHORIZATION_UNAVAILABLE)", down[0] == 503 and down[2]["code"] == "AUTHORIZATION_UNAVAILABLE")
        if args.newman:
            report["newman"].append(run_newman("03 S01 unavailable", variables, args.newman_cli, temp))
        check("Denied and failed reads still leave state unchanged", snapshot() == before)
        report["dependencies"] = [
            {"id": "API-120 real PASS", "status": "BLOCKED", "scope": "Needs the S02 source adapter; MANAGER_APPROVAL versions are SQL fixtures"},
            {"id": "Production repository grants", "status": "NOT_VERIFIED", "scope": "Controlled SQL VERIFIED grant, not Request/Verify/Activate or S02 binding"},
            {"id": "API-124 eligibility", "status": "OUT_OF_SCOPE", "scope": "No Self Final Approval and API-039 fresh authorization belong to API-124"}]
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
        infra.stop()
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Evidence: {args.output}", flush=True)
        temporary.cleanup()


if __name__ == "__main__":
    main()
