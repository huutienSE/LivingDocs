"""Real local S01/Gateway/S02/PostgreSQL/Redis + existing GitHub App.

Only creates a fresh task database and a uniquely named disposable Redis. Never
modifies the owner's DB, App, repository or credentials. JSON evidence excludes tokens.
Requires compiled production classes, packaged dependency jars, Java, PostgreSQL and Docker.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / 'backend/repository-integration-service/target'
TENANT = '12200000-0000-4000-8000-000000000001'
WORKSPACE = '12200000-0000-4000-8000-000000000002'
CONNECTION = '12200000-0000-4000-8000-000000000004'
JAVA = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
PG = Path(os.environ.get('LIV151_PG_BIN', 'C:/Program Files/PostgreSQL/18/bin'))
DB_PORT = '58451'
ADMIN = 'liv151_test_admin'
HIDDEN = getattr(subprocess, 'CREATE_NO_WINDOW', 0)


def run(args, **kwargs):
    if Path(str(args[0])).name == 'pg_ctl.exe':
        result = subprocess.run([str(x) for x in args], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=HIDDEN, **kwargs)
    else:
        result = subprocess.run([str(x) for x in args], capture_output=True, creationflags=HIDDEN, **kwargs)
    if result.returncode:
        (TARGET/'liv151-flow1-runner-error.log').write_bytes(result.stderr or b'')
        raise RuntimeError('Local fixture command failed: ' + Path(str(args[0])).name)
    return result.stdout or b''


def sql(database, statement):
    return run([PG/'psql.exe', '-X', '-h', '127.0.0.1', '-p', DB_PORT, '-U', ADMIN, '-d', database,
                '-v', 'ON_ERROR_STOP=1', '-At'], input=statement.encode()).decode().strip()


def http(base, path, method='GET', body=None, bearer=None, headers=None, expected=200):
    request_headers = dict(headers or {})
    request_headers['X-Correlation-ID'] = 'flow1-' + str(uuid.uuid4())
    if bearer:
        request_headers['Authorization'] = 'Bearer ' + bearer
    data = None if body is None else json.dumps(body).encode()
    if data is not None:
        request_headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(base + path, data=data, headers=request_headers, method=method)
    try:
        response = urllib.request.urlopen(request, timeout=25)
    except urllib.error.HTTPError as error:
        response = error
    content = response.read()
    if response.status != expected:
        # Log stable error code only, never raw response/token/URL query.
        try:
            code = json.loads(content).get('code', 'NO_CODE')
        except ValueError:
            code = 'NON_JSON'
        raise AssertionError(f'{method} {path.split("?")[0]} expected {expected}, received {response.status}/{code}')
    return json.loads(content) if content else {}


def wait_ready(base, child=None):
    end = time.monotonic() + 80
    while time.monotonic() < end:
        if child is not None and child.poll() is not None:
            raise RuntimeError('Service exited before readiness: '+base)
        try:
            http(base, '/actuator/health')
            return
        except Exception:
            time.sleep(.5)
    raise RuntimeError('Local service failed to become healthy: ' + base)


def dependencies(module, destination):
    destination.mkdir(parents=True, exist_ok=True)
    build_root=ROOT/module/'target'
    jars = sorted((build_root/'liv151-e2e-build').glob('*.jar')) + sorted((build_root/'liv151-build').glob('*.jar')) + sorted(build_root.glob('*.jar'))
    for jar in jars:
        try:
            with zipfile.ZipFile(jar) as archive:
                libraries = [n for n in archive.namelist() if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')]
                if not libraries:
                    continue
                for name in libraries:
                    output = destination/Path(name).name
                    if not output.exists():
                        output.write_bytes(archive.read(name))
                return destination
        except zipfile.BadZipFile:
            continue
    raise RuntimeError('A packaged dependency jar is required for ' + module)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', default='livingdocs_liv151_flow1_' + time.strftime('%m%d%H%M%S'))
    parser.add_argument('--key-reference', default='')
    args = parser.parse_args()
    if not args.database.startswith('livingdocs_liv151_flow1_') or not args.database.replace('_', '').isalnum():
        raise SystemExit('Only a fresh task-specific fixture database is accepted')
    for port in (18480,18481,18482,18491,58479):
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1',port)) == 0:
                raise SystemExit('Fixture port already in use; refusing to contact an existing service: '+str(port))
    out = TARGET/args.database
    out.mkdir(parents=True, exist_ok=False)
    cluster = TARGET/'liv151-postgres-20261127'
    children, streams = [], []
    redis_name = args.database.replace('_', '-') + '-redis'
    pg_started = redis_started = False
    base_s01, base_s02, gateway = 'http://127.0.0.1:18481', 'http://127.0.0.1:18482', 'http://127.0.0.1:18480'
    evidence = {'environment': 'isolated local HTTP, real S01/S02/Gateway/PG18/Redis; real GitHub read only',
                'checks': {}, 'database': args.database}
    try:
        status = subprocess.run([str(PG/'pg_ctl.exe'), '-D', str(cluster), 'status'], capture_output=True, creationflags=HIDDEN)
        if status.returncode == 0:
            raise RuntimeError('Task PostgreSQL is in use; refusing to stop or reuse another run')
        run([PG/'pg_ctl.exe', '-D', cluster, '-l', out/'postgres.log', '-o',
             '-p 58451 -h 127.0.0.1 -c max_connections=100 -c shared_buffers=16MB', '-w', 'start'])
        pg_started = True
        sql('postgres', 'CREATE DATABASE ' + args.database + ' OWNER ld_s02_migrator;')
        sql(args.database, 'CREATE SCHEMA repository AUTHORIZATION ld_s02_migrator; GRANT USAGE ON SCHEMA repository TO ld_s02_app;')
        if sql('postgres', "SELECT count(*) FROM pg_roles WHERE rolname='ld_s01_app'") == '0':
            sql(args.database, (ROOT/'backend/identity-organization-service/src/test/resources/s01-test-schema.sql').read_text().replace('AUTHORIZATION postgres', 'AUTHORIZATION '+ADMIN))
        else:
            sql(args.database, 'CREATE SCHEMA identity AUTHORIZATION '+ADMIN+'; GRANT USAGE ON SCHEMA identity TO ld_s01_app,ld_s01_provisioner,ld_s01_bootstrap;')
        run(['docker', 'run', '--rm', '-d', '--name', redis_name, '-p', '127.0.0.1:58479:6379', 'redis:8.2-alpine'])
        redis_started = True
        s01libs = dependencies('backend/identity-organization-service', out/'s01-libs')
        s02libs = dependencies('backend/repository-integration-service', out/'s02-libs')
        gatewaylibs = dependencies('infrastructure/api-gateway', out/'gateway-libs')
        keys = out/'keys'
        run([JAVA, '-cp', str(s01libs/'*'), ROOT/'tests/e2e/Flow1FixtureKeys.java', keys, TENANT])
        common = os.environ.copy()
        common.update(DB_URL='jdbc:postgresql://127.0.0.1:58451/'+args.database, SPRING_PROFILES_ACTIVE='db',
                      REDIS_HOST='127.0.0.1', REDIS_PORT='58479')

        def launch(name, classes, libs, main_class, env, properties):
            stream = (out/(name+'.log')).open('wb'); streams.append(stream)
            command = [str(JAVA), '-Xmx256m', '-cp', str(classes)+os.pathsep+str(libs/'*'), main_class]
            command += ['--'+k+'='+str(v) for k, v in properties.items()]
            child = subprocess.Popen(command, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT, creationflags=HIDDEN)
            children.append(child)
            return child

        s01env = common | dict(DB_SCHEMA='identity', DB_USERNAME='ld_s01_app', DB_PASSWORD='test_app_password',
                FLYWAY_USERNAME=ADMIN, FLYWAY_PASSWORD='', SERVER_PORT='18481', JWT_PRIVATE_KEY_PATH=str(keys/'s01-private.pem'),
                JWT_PUBLIC_KEY_PATH=str(keys/'s01-public.pem'), LOGIN_RATE_LIMIT_PEPPER_PATH=str(keys/'pepper'),
                S01_WORKLOAD_REGISTRY_PATH=str(keys/'workloads.json'), S01_INTERNAL_ALLOW_INSECURE_TRANSPORT='true',
                S02_BINDING_BASE_URL=base_s02, S02_BINDING_ALLOW_HTTP='true')
        s01_child = launch('s01', ROOT/'backend/identity-organization-service/target/liv151-e2e-build/classes', s01libs,
               'com.livingdocs.identity.IdentityOrganizationServiceApplication', s01env, {'spring.config.import':''})
        wait_ready(base_s01, s01_child)
        s02env = common | dict(DB_SCHEMA='repository', DB_USERNAME='ld_s02_app', DB_PASSWORD='liv151-fixture-only',
                FLYWAY_USERNAME='ld_s02_migrator', FLYWAY_PASSWORD='liv151-fixture-only', SERVER_PORT='18482',
                LIVINGDOCS_SOURCE_LOOKUP_GITHUBREADYSTATUSES='CONNECTED')
        s02_child = launch('s02', (TARGET/'liv151-e2e-build/classes' if (TARGET/'liv151-e2e-build/classes').exists() else TARGET/'liv151-build/classes'), s02libs, 'com.livingdocs.RepositoryIntegrationServiceApplication', s02env,
               {'livingdocs.source.s01.enabled':'true', 'livingdocs.source.s01.jwk-set-uri':base_s01+'/.well-known/jwks.json',
                'livingdocs.source.s01.allow-local-http':'true', 'livingdocs.source.http.allow-insecure-transport':'true',
                'livingdocs.github.authorization.allow-local-http':'true', 'livingdocs.github.authorization.s01-base-uri':base_s01,
                'livingdocs.github.authorization.jwk-set-uri':base_s01+'/.well-known/jwks.json',
                'livingdocs.github.authorization.cleanup-enabled':'false'})
        wait_ready(base_s02, s02_child)
        # Read only public configuration references; raw credentials remain in the existing private mount.
        public = {}
        private_config = ROOT/'backend/repository-integration-service/.env'
        for line in private_config.read_text(encoding='utf-8-sig').splitlines():
            key, _, value = line.partition('=')
            if key.strip() in ('LIVINGDOCS_SOURCE_GITHUB_KEYDIRECTORY', 'LIVINGDOCS_SOURCE_GITHUB_CLIENTID'):
                public[key.strip()] = value.strip().replace('\\', '/')
        key_dir = Path(os.environ.get('LIVINGDOCS_SOURCE_GITHUB_KEYDIRECTORY', public.get('LIVINGDOCS_SOURCE_GITHUB_KEYDIRECTORY','')))
        candidates = list(key_dir.glob('*.pem')) if key_dir.is_dir() else []
        key_ref = args.key_reference or (candidates[0].name if len(candidates)==1 else '')
        if not key_ref or not (key_dir/key_ref).is_file() or Path(key_ref).name != key_ref:
            raise RuntimeError('Select the existing App key filename with --key-reference; no credentials are printed')
        fixture = (ROOT/'backend/repository-integration-service/src/test/resources/liv122/github-live-fixture.sql').read_bytes()
        run([PG/'psql.exe','-X','-h','127.0.0.1','-p',DB_PORT,'-U',ADMIN,'-d',args.database,
             '-v','installation_id=183597993','-v','repository_id=1463763176','-v','credential_ref='+key_ref],input=fixture)

        gatewayenv = common | dict(SPRING_PROFILES_ACTIVE='', GATEWAY_PORT='18480', GATEWAY_MANAGEMENT_PORT='18491',
                S01_SERVICE_URI=base_s01, S02_SERVICE_URI=base_s02, JWT_ISSUER_URI='urn:livingdocs:s01',
                JWT_AUDIENCE='livingdocs-api', JWT_JWK_SET_URI=base_s01+'/.well-known/jwks.json')
        gateway_child = launch('gateway', ROOT/'infrastructure/api-gateway/target/liv151-e2e-build/classes', gatewaylibs,
                'com.livingdocs.gateway.GatewayApplication', gatewayenv, {'spring.config.import':''})
        # Management is intentionally not exposed via the Gateway public surface.
        for _ in range(100):
            try:
                http(gateway,'/api/v1/users/me',expected=401); break
            except Exception:
                if gateway_child.poll() is not None: raise RuntimeError('Gateway did not start')
                time.sleep(.3)
        password = 'Flow1-fixture-only-password-123!'
        identities = {}
        for role in ('lead','owner'):
            email = role+'-'+uuid.uuid4().hex+'@example.test'
            created = http(gateway,'/api/v1/auth/register','POST',{'email':email,'password':password},expected=201)
            identities[role] = created['data']['identityId']
            identities[role+'_email'] = email
        member, tm_lead, tm_owner = str(uuid.uuid4()),str(uuid.uuid4()),str(uuid.uuid4())
        sql(args.database, f"""
            INSERT INTO identity.tenants(tenant_id,name) VALUES ('{TENANT}','Flow1 isolated E2E');
            INSERT INTO identity.tenant_memberships(membership_id,tenant_id,identity_id) VALUES
              ('{tm_lead}','{TENANT}','{identities['lead']}'),('{tm_owner}','{TENANT}','{identities['owner']}');
            INSERT INTO identity.tenant_role_assignments(tenant_id,tenant_membership_id,role_code) VALUES ('{TENANT}','{tm_owner}','TENANT_OWNER');
            INSERT INTO identity.workspaces(workspace_id,tenant_id,name) VALUES ('{WORKSPACE}','{TENANT}','Flow1');
            INSERT INTO identity.workspace_memberships(workspace_membership_id,tenant_id,workspace_id,tenant_membership_id,identity_id)
              VALUES ('{member}','{TENANT}','{WORKSPACE}','{tm_lead}','{identities['lead']}');
            INSERT INTO identity.workspace_role_assignments(tenant_id,workspace_id,workspace_membership_id,role_code)
              VALUES ('{TENANT}','{WORKSPACE}','{member}','TECHNICAL_LEAD'),('{TENANT}','{WORKSPACE}','{member}','DEVELOPER');
        """)
        tokens = {role:http(gateway,'/api/v1/auth/login','POST',{'email':identities[role+'_email'],'password':password})['data']['accessToken']
                  for role in ('lead','owner')}
        checks = evidence['checks']
        # Provider registration evidence only; never log App JWT or private-key bytes.
        client_id=os.environ.get('LIVINGDOCS_SOURCE_GITHUB_CLIENTID',public.get('LIVINGDOCS_SOURCE_GITHUB_CLIENTID',''))
        def encode(value): return base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).rstrip(b'=')
        signing=encode(dict(alg='RS256',typ='JWT'))+b'.'+encode(dict(iss=client_id,iat=int(time.time())-60,exp=int(time.time())+240))
        signature=run([os.environ.get('OPENSSL_BIN','C:/msys64/ucrt64/bin/openssl.exe'),'dgst','-sha256','-sign',key_dir/key_ref],input=signing)
        app_jwt=(signing+b'.'+base64.urlsafe_b64encode(signature).rstrip(b'=')).decode()
        app=http('https://api.github.com','/app',bearer=app_jwt,headers={'Accept':'application/vnd.github+json','User-Agent':'LivingDocs-Flow1-E2E','X-GitHub-Api-Version':'2026-03-10'})
        evidence['githubApp']={key:app[key] for key in ('id','slug','name','permissions','events')}
        evidence['liv151ProfileMatches']=(app['permissions']==dict(metadata='read',contents='write',pull_requests='write',checks='write') and set(app['events'])=={'push','pull_request'})
        prefix = f'/api/v1/tenants/{TENANT}/workspaces/{WORKSPACE}/repository-grants'
        request = {'workspaceMembershipId':member,'repositoryConnectionId':CONNECTION,'provenance':'Real S01-S02 isolated Flow1 E2E'}
        created = http(gateway,prefix,'POST',request,tokens['lead'],{'Idempotency-Key':str(uuid.uuid4())},201)['data']
        assert not created['effective'] and created['verificationStatus']=='PENDING'
        checks['request_pending'] = 'PASS'
        grant = created['grantId']
        http(gateway,prefix+'/'+grant+'/verify','POST',bearer=tokens['lead'],expected=403)
        verified = http(gateway,prefix+'/'+grant+'/verify','POST',bearer=tokens['owner'])['data']
        assert verified['effective'] and verified['verificationStatus']=='VERIFIED'
        checks['owner_verifies_via_real_s02_binding'] = 'PASS'
        access = http(gateway,'/api/v1/users/me/access-context',bearer=tokens['lead'])['data']
        assert CONNECTION in next(g for g in access['workspaceGrants'] if g['workspaceId']==WORKSPACE)['repositoryIds']
        checks['api038_effective_repository'] = 'PASS'

        def issue(workload, operation, path, audience='livingdocs-s02-internal', **extra):
            secret = (keys/(workload+'.secret')).read_text()
            basic = base64.b64encode((workload+':'+secret).encode()).decode()
            body = dict(audience=audience,operation=operation,method='POST',path=path,tenantId=TENANT,
                        workspaceId=WORKSPACE,repositoryConnectionId=CONNECTION) | extra
            return http(base_s01,'/internal/v1/auth/service-tokens','POST',body,headers={'Authorization':'Basic '+basic})['data']['accessToken']

        def evaluate(expected):
            action='DOCUMENT_AUTHOR'
            assertion=issue('s04','authorization:evaluate','/internal/v1/authorization/evaluate','livingdocs-s01-internal',
                            action=action,actorTokenSha256=hashlib.sha256(tokens['lead'].encode()).hexdigest())
            body=dict(action=action,tenantId=TENANT,workspaceId=WORKSPACE,repositoryConnectionId=CONNECTION)
            result=http(base_s01,'/internal/v1/authorization/evaluate','POST',body,tokens['lead'],{'X-LD-Service-Assertion':'Bearer '+assertion})
            assert result['data']['decision']==expected

        evaluate('ALLOW'); checks['api039_live_grant_allow']='PASS'
        resolve_path,validate_path='/internal/v1/source/resolve','/internal/v1/source/validate'
        resolve_token=issue('s03','source:resolve',resolve_path)
        resolve_body=dict(repositoryConnectionId=CONNECTION,targetKind='TRACKED_BRANCH',targetRef='refs/heads/main')
        resolved=http(base_s02,resolve_path,'POST',resolve_body,resolve_token)['data']
        sha=resolved['resolvedSha']; assert len(sha)==40
        evidence['resolvedSha']=sha
        validate_body=resolve_body | dict(boundHeadSha=sha,configRevision=resolved['configRevision'])
        validation_token=issue('s04','source:validate',validate_path)
        receipt=http(base_s02,validate_path,'POST',validate_body,validation_token)['data']
        assert receipt['validationStatus']=='PASS' and receipt['validatedHeadSha']==sha
        checks['s01_issued_s03_s04_jwt_real_github_sha']='PASS'
        http(base_s02,validate_path,'POST',validate_body,resolve_token,expected=403)
        http(base_s02,resolve_path,'POST',resolve_body,tokens['lead'],expected=401)
        http(gateway,resolve_path,'POST',resolve_body,tokens['lead'],expected=403)
        http(base_s02,resolve_path,'POST',resolve_body|dict(repositoryConnectionId=str(uuid.uuid4())),resolve_token,expected=403)
        wrong_sha=('a' if sha[0]!='a' else 'b')+sha[1:]
        http(base_s02,validate_path,'POST',validate_body|dict(boundHeadSha=wrong_sha),validation_token,expected=409)
        checks['wrong_operation_user_token_scope_public_route_stale_sha']='PASS'
        # Mutate only this fresh fixture DB. Existing App/repository and owner data stay unchanged.
        sql(args.database,f"BEGIN; SET LOCAL ROLE ld_s02_app; SELECT set_config('app.tenant_id','{TENANT}',true); UPDATE repository.repository_connections SET status='DISCONNECTED' WHERE repository_connection_id='{CONNECTION}'; COMMIT;")
        evaluate('DENY'); checks['disconnect_invalidates_grant']='PASS'
        sql(args.database,f"BEGIN; SET LOCAL ROLE ld_s02_app; SELECT set_config('app.tenant_id','{TENANT}',true); UPDATE repository.repository_connections SET status='ACTIVE' WHERE repository_connection_id='{CONNECTION}'; COMMIT;")
        evaluate('DENY'); checks['restore_does_not_revive_old_verified_grant']='PASS'
        revoked=http(gateway,prefix+'/'+grant+'/revoke','POST',bearer=tokens['lead'])['data']
        assert revoked['status']=='REVOKED'; evaluate('DENY'); checks['revoke_and_fresh_deny']='PASS'
        evidence['result']='PASS'
    except Exception as error:
        evidence['result']='FAIL'
        evidence['failure']=str(error)
        raise
    finally:
        for child in reversed(children):
            child.terminate()
            try: child.wait(timeout=15)
            except subprocess.TimeoutExpired: child.kill();child.wait()
        for stream in streams: stream.close()
        if redis_started:
            subprocess.run(['docker','stop',redis_name],capture_output=True,creationflags=HIDDEN)
        if pg_started:
            subprocess.run([str(PG/'pg_ctl.exe'),'-D',str(cluster),'-m','fast','-w','stop'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=HIDDEN)
        (out/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n',encoding='utf-8')
        print(json.dumps(evidence,indent=2))


if __name__ == '__main__':
    main()
