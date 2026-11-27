"""Validate API-046 static contract and optional HTTP/Springdoc fixture evidence.

Dependencies: PyYAML, jsonschema. This does not claim real S01/GitHub E2E.
"""
import argparse
import json
from pathlib import Path

import yaml
from jsonschema import Draft4Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[1]
ROUTE = '/api/v1/integrations/github/authorization'


def require(condition, message):
    if not condition:
        raise SystemExit('FAIL LIV-151: ' + message)


def validate_http_body(spec, operation, status, media, body):
    schema = operation['responses'][status]['content'][media]['schema']
    Draft4Validator({**schema, 'components': spec['components']},
                    format_checker=FormatChecker()).validate(body)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence-dir', type=Path)
    args = parser.parse_args()
    spec = yaml.safe_load((ROOT / 'contracts/openapi/s02-repository.yaml').read_text(encoding='utf-8'))
    operation = spec['paths'][ROUTE]['get']
    inventory = json.loads((ROOT / 'contracts/openapi/sprint3-route-inventory.json').read_text(encoding='utf-8'))
    row = next(row for row in inventory if row['apiId'] == 'API-046')
    require(operation['operationId'] == 'api046_get', 'operationId changed')
    require(operation['x-contract-state'] == row['state'] == 'IN_PROGRESS', 'partial implementation must remain IN_PROGRESS')
    require(operation['security'] == [{'bearerAuth': []}], 'human JWT security is required')
    require([p['name'] for p in operation['parameters']] == [
        'workspaceId', 'authorizationContextId', 'installationId', 'installationPage'], 'query contract drift')
    require(operation['parameters'][0]['required'], 'workspaceId must be required')
    require(set(operation['responses']) == {'200', '400', '401', '403', '404', '405', '406', '500', '503'},
            'response status contract drift')
    for status, response in operation['responses'].items():
        expected_media = 'application/json' if status == '200' else 'application/problem+json'
        require(set(response['content']) == {expected_media}, 'wrong response media type for ' + status)
        require(response['headers']['Cache-Control']['schema']['enum'] == ['no-store'], 'missing no-store')
        require('X-Correlation-ID' in response['headers'], 'missing correlation header')
        require('CandidateObject' not in json.dumps(response['content']), 'untyped response')
    if args.evidence_dir:
        runtime = json.loads((args.evidence_dir / 'runtime-openapi.json').read_text(encoding='utf-8'))
        observed = runtime['paths'][ROUTE]['get']
        for field in ('operationId', 'parameters', 'responses', 'security', 'x-contract-state'):
            require(observed[field] == operation[field], 'Springdoc/static drift: ' + field)
        for name in ('http-success.json', 'http-problem.json'):
            body = json.loads((args.evidence_dir / name).read_text(encoding='utf-8'))
            status, media = ('200', 'application/json') if name == 'http-success.json' else ('401', 'application/problem+json')
            validate_http_body(spec, operation, status, media, body)
            validate_http_body(runtime, observed, status, media, body)
            require(not any(secret in json.dumps(body).lower() for secret in (
                'bearer ', 'private_key', 'credential_ref', 'access_token', 'refresh_token')), 'sensitive response field')
            if status == '200':
                view = body['data']
                require(body['code'] == 'GITHUB_AUTHORIZATION_CONTEXT_READ', 'wrong success code')
                require(view['verification'] == {'status': 'USER_AUTHORIZATION_REQUIRED'}, 'unverified result claims access')
                require({(p['name'], p['level']) for p in view['requiredPermissions']} == {
                    ('metadata', 'read'), ('contents', 'write'), ('pull_requests', 'write'), ('checks', 'write')},
                    'required permissions do not match the approved App profile')
                require(set(view['requiredEvents']) == {'push', 'pull_request'}, 'wrong App events')
        print('PASS: API-046 static / runtime Springdoc / HTTP success and Problem fixtures match.')
    else:
        print('PASS: API-046 static contract only; runtime and HTTP evidence NOT RUN.')
    print('NOTE: HTTP fixtures use controlled S01/JWKS; real provider/Gateway E2E is not verified.')


if __name__ == '__main__':
    main()
