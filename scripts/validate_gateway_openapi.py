from collections import defaultdict
import json
from pathlib import Path
import sys
import yaml

ROOT = Path(__file__).resolve().parents[1]
INVENTORY = ROOT / 'contracts/openapi/sprint3-route-inventory.json'
GATEWAY = ROOT / 'infrastructure/api-gateway/src/main/resources/application.yaml'
SPECS = ROOT / 'contracts/openapi'
SERVICE_PREFIX = {'S01': 's01', 'S02': 's02', 'S03': 's03', 'S04': 's04'}
BLOCKED = {'API-003', 'API-004', 'API-005', 'API-006', 'API-068'}


def fail(message):
    raise SystemExit('FAIL LIV-133: ' + message)

def read_yaml(path):
    with path.open(encoding='utf-8') as stream:
        return yaml.safe_load(stream)

def validate_register_contract(inventory):
    spec = read_yaml(SPECS / 's01-identity.yaml')
    operation = spec['paths']['/api/v1/auth/register']['post']
    row = next(row for row in inventory if row['apiId'] == 'API-001')
    if row['method'] != 'POST' or row['path'] != '/api/v1/auth/register':
        fail('API-001 inventory method/path sai.')
    if operation.get('x-contract-state') != row['state'] or row['state'] not in {'IN_PROGRESS', 'IMPLEMENTED'}:
        fail('API-001 state không khớp inventory.')
    schemas = spec['components']['schemas']
    response = operation['responses']['201']['content']['application/json']['schema']
    if response != {'$ref': '#/components/schemas/RegisterApiResponse'}:
        fail('API-001 201 phải dùng RegisterApiResponse, không dùng CandidateObject.')
    envelope = schemas['RegisterApiResponse']['allOf']
    if envelope[0] != {'$ref': '#/components/schemas/ApiResponseBase'}:
        fail('API-001 thiếu ApiResponseBase.')
    base = schemas['ApiResponseBase']
    data = envelope[1]
    if not {'code', 'message'} <= set(base.get('required', [])) or 'data' not in data.get('required', []):
        fail('API-001 201 thiếu required code/message/data.')
    if data['properties']['data'] != {'$ref': '#/components/schemas/RegisterResponse'}:
        fail('API-001 data sai type.')
    register_response = schemas['RegisterResponse']
    if register_response.get('required') != ['identityId'] or register_response['properties']['identityId'] != {'type': 'string', 'format': 'uuid'}:
        fail('API-001 identityId phải là UUID bắt buộc.')
    request = operation['requestBody']['content']['application/json']['schema']
    if request != {'$ref': '#/components/schemas/RegisterRequest'}:
        fail('API-001 request schema sai.')
    request_schema = schemas['RegisterRequest']
    if set(request_schema.get('required', [])) != {'email', 'password'}:
        fail('API-001 thiếu email/password bắt buộc.')
    if request_schema['properties']['email'].get('maxLength') != 320:
        fail('API-001 email maxLength sai.')
    password = request_schema['properties']['password']
    if password.get('minLength') != 12 or password.get('maxLength') != 64:
        fail('API-001 password length sai.')
    problem = spec['components']['responses']['Problem']['content'].get('application/problem+json', {})
    if problem.get('schema') != {'$ref': '#/components/schemas/Problem'}:
        fail('API-001 error Content-Type/schema sai.')
    for status in ('400', '409', '415', '500'):
        error_response = operation['responses'].get(status)
        if error_response == {'$ref': '#/components/responses/Problem'}:
            continue
        if not isinstance(error_response, dict):
            fail(f'API-001 {status} phải dùng RFC 9457 Problem.')
        content = error_response.get('content')
        if not isinstance(content, dict) or set(content) != {'application/problem+json'}:
            fail(f'API-001 {status} phải dùng application/problem+json.')
        media_type = content['application/problem+json']
        if not isinstance(media_type, dict) or media_type.get('schema') != {'$ref': '#/components/schemas/Problem'}:
            fail(f'API-001 {status} phải dùng RFC 9457 Problem schema.')

def main():
    inventory = json.loads(INVENTORY.read_text(encoding='utf-8'))
    if len(inventory) < 60:
        fail('Danh mục operation bị cắt giảm bất thường (<60).')
    ids = [row['apiId'] for row in inventory]
    if len(ids) != len(set(ids)):
        fail('Trùng API ID trong inventory.')

    expected = {}
    for row in inventory:
        key = row['method'], row['path']
        if row['gatewayEnabled']:
            if row['apiId'] in BLOCKED or row['path'].startswith('/internal/') or row['service'] == 'S05':
                fail('API nhạy cảm/internal không được public: ' + str(row))
            if key in expected:
                fail('Trùng allowlist method+path trong inventory: ' + str(key))
            expected[key] = row['service']

    conf = read_yaml(GATEWAY)
    routes = conf['spring']['cloud']['gateway']['server']['webflux']['routes']
    actual = {}
    for route in routes:
        rid = route['id']
        predicates = route['predicates']
        method_predicates = [p[7:] for p in predicates if p.startswith('Method=')]
        path_predicates = [p[5:] for p in predicates if p.startswith('Path=')]
        if len(method_predicates) != 1 or len(path_predicates) != 1:
            fail(f'{rid}: cần đúng 1 Method và 1 Path predicate.')
        methods = method_predicates[0].split(',')
        if len(methods) != 1:
            fail(f'{rid}: không nhóm nhiều Method vì có thể mở route ngoài ý muốn.')
        method = methods[0].strip()
        route_service = next((s for s, prefix in SERVICE_PREFIX.items() if rid.startswith(prefix + '-')), None)
        if route_service is None:
            fail(f'{rid}: route thiếu service prefix xác định.')
        for path in path_predicates[0].split(','):
            path = path.strip()
            if '**' in path or '/internal/' in path or path.startswith('/actuator') or path.startswith('/v3/api-docs') or path.startswith('/swagger-ui'):
                fail(f'{rid}: wildcard hoặc endpoint nội bộ không hợp lệ: {path}')
            if (method, path) in actual:
                fail(f'{rid}: method+path được route trùng: {method} {path}')
            actual[(method, path)] = route_service

    missing = set(expected) - set(actual)
    extra = set(actual) - set(expected)
    mismatch = {k: (expected[k], actual[k]) for k in set(expected) & set(actual) if expected[k] != actual[k]}
    if missing or extra or mismatch:
        fail('Routes không khớp inventory:\n'
             f'  Thiếu: {sorted(missing)}\n  Thừa: {sorted(extra)}\n  Sai service: {mismatch}')

    # OpenAPI candidates must match inventory by path/method; API-120 deliberately
    # lives in the separate authoritative PR LIV-118 file, not duplicated here.
    described = {}
    for file in sorted(SPECS.glob('s0*.yaml')):
        spec = read_yaml(file)
        if not isinstance(spec, dict) or spec.get('openapi') != '3.0.3':
            fail(f'Invalid OpenAPI 3.0.3 header in {file}')
        for path, methods in spec['paths'].items():
            for method, operation in methods.items():
                if method.lower() not in {'get', 'post', 'put', 'patch', 'delete'}:
                    continue
                key = (method.upper(), path)
                api_id = operation.get('x-api-id')
                if api_id == 'S05-HEALTH':
                    continue
                if not api_id:
                    # PR LIV-118 standalone S04 spec is authoritative for API-120.
                    if file.name == 's04-document-review.yaml' and key == ('POST', '/api/v1/document-versions/{versionId}/staff-decisions'):
                        continue
                    fail(f'{file}: thiếu x-api-id cho {key}')
                if api_id not in ids:
                    fail(f'{file}: {api_id} không thuộc inventory')
                if key in described:
                    fail(f'Method+path mô tả trùng tại {file}: {key}')
                described[key] = api_id
    unrepresented = {tuple((r['method'],r['path'])) for r in inventory if r['apiId'] != 'API-120'} - set(described)
    if unrepresented:
        fail('Thiếu operations trong spec: '+str(sorted(unrepresented)))
    validate_register_contract(inventory)
    # Only static config assertions here. Real JWT/JWKS E2E remains blocked by S01.
    cors_source = (ROOT / 'infrastructure/api-gateway/src/main/java/com/livingdocs/gateway/config/GatewayCorsConfig.java').read_text(encoding='utf-8')
    for required in ('"If-Match"', '"Idempotency-Key"', '"ETag"', 'cors.setAllowCredentials(false)'):
        if required not in cors_source:
            fail('Gateway CORS thiếu/rộng hơn security baseline: ' + required)
    if 'S03_SERVICE_URI' not in conf['spring']['cloud']['gateway']['server']['webflux']['routes'][0]['uri'] and not any('S03_SERVICE_URI' in x['uri'] for x in routes):
        fail('Gateway thiếu URI route cho S03')
    compose = read_yaml(ROOT / 'infrastructure/docker/compose.yaml')
    if compose['services']['api-gateway']['environment'].get('S03_SERVICE_URI') != 'http://source-intelligence-service:8083':
        fail('Docker Compose Gateway chưa khai báo S03 service DNS.')
    print(f'PASS: {len(inventory)} catalogued APIs; {len(actual)} exact Gateway method+path entries; {len(routes)} route groups; no internal exposure.')
    print('NOTE: STATIC validation only; backend controllers/JWT/CSRF/E2E are not verified.')


if __name__ == '__main__':
    main()
