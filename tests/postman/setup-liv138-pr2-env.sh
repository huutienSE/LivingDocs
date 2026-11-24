#!/usr/bin/env bash
set -euo pipefail

s01_base="${S01_BASE_URL:-http://127.0.0.1:8081}"
gateway_base="${GATEWAY_BASE_URL:-http://127.0.0.1:8080}"
case "${s01_base%/}" in
  http://127.0.0.1:8081|http://localhost:8081) ;;
  *) printf 'Only local S01 on port 8081 is allowed.\n' >&2; exit 2 ;;
esac
case "${gateway_base%/}" in
  http://127.0.0.1:8080|http://localhost:8080) ;;
  *) printf 'Only local Gateway on port 8080 is allowed.\n' >&2; exit 2 ;;
esac

b_email="${LIV138_PR2_B_EMAIL:-}"
tenant_id="${LIV138_PR2_TENANT_ID:-}"
workspace_id="${LIV138_PR2_WORKSPACE_ID:-}"
other_workspace_id="${LIV138_PR2_OTHER_WORKSPACE_ID:-}"
repository_id="${LIV138_PR2_REPOSITORY_ID:-}"
secret_dir="${LIV138_PR2_SECRET_DIR:-$HOME/.livingdocs-pr2}"

[[ -n "$b_email" ]] || read -r -p 'Provisioned B Identity email: ' b_email
[[ -n "$tenant_id" ]] || read -r -p 'Tenant UUID: ' tenant_id
[[ -n "$workspace_id" ]] || read -r -p 'Review Workspace UUID: ' workspace_id
[[ -n "$other_workspace_id" ]] || read -r -p 'Other Workspace UUID: ' other_workspace_id
[[ -n "$repository_id" ]] || read -r -p 'S01 PENDING grant repository reference UUID: ' repository_id

uuid_regex='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
if [[ "$b_email" != *@* || ! "$tenant_id" =~ $uuid_regex ||
      ! "$workspace_id" =~ $uuid_regex || ! "$other_workspace_id" =~ $uuid_regex ||
      ! "$repository_id" =~ $uuid_regex || "$workspace_id" == "$other_workspace_id" ]]; then
  printf 'B email and distinct Tenant/Workspace/Repository UUIDs are required.\n' >&2
  exit 2
fi
for workload in s02 s03 s04; do
  if [[ ! -r "$secret_dir/$workload.secret" ]]; then
    printf 'Missing readable workload secret: %s/%s.secret\n' "$secret_dir" "$workload" >&2
    exit 2
  fi
done

b_password="${LIV138_PR2_B_PASSWORD:-}"
if [[ -z "$b_password" ]]; then
  read -r -s -p 'Provisioned B Identity password: ' b_password
  printf '\n'
fi
[[ -n "$b_password" ]] || { printf 'B Identity password is required.\n' >&2; exit 2; }

umask 077
environment_dir="$(mktemp -d "${TMPDIR:-/tmp}/liv138-pr2-postman.XXXXXX")"
environment_file="$environment_dir/liv138-pr2.postman_environment.json"

LIV138_PR2_B_PASSWORD="$b_password" python3 - "$environment_file" "$secret_dir" \
  "$b_email" "$tenant_id" "$workspace_id" "$other_workspace_id" "$repository_id" \
  "${s01_base%/}" "${gateway_base%/}" <<'PY'
import base64
import json
import os
import sys
from pathlib import Path

environment_file, secret_dir, email, tenant, workspace, other_workspace, repository, s01_base, gateway_base = sys.argv[1:]

def basic(workload):
    secret = (Path(secret_dir) / f'{workload}.secret').read_text().strip()
    if not secret:
        raise SystemExit(f'Empty {workload} secret')
    return base64.b64encode(f'{workload}:{secret}'.encode()).decode()

fields = {
    's01Base': s01_base,
    'gatewayUrl': gateway_base,
    'bEmail': email,
    'bPassword': os.environ['LIV138_PR2_B_PASSWORD'],
    'tenantId': tenant,
    'workspaceId': workspace,
    'otherWorkspaceId': other_workspace,
    'repositoryConnectionId': repository,
    's02WorkloadBasic': basic('s02'),
    's03WorkloadBasic': basic('s03'),
    's04WorkloadBasic': basic('s04'),
    'invalidWorkloadBasic': base64.b64encode(b's02:wrong-secret').decode(),
}
document = {
    'name': 'LIV-138 PR2 Local S01',
    'values': [
        {'key': key, 'value': value, 'type': 'default', 'enabled': True}
        for key, value in fields.items()
    ],
    '_postman_variable_scope': 'environment',
}
Path(environment_file).write_text(json.dumps(document, indent=2) + '\n')
PY
unset -v b_password

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
printf 'Postman collection: %s/liv138-pr2.postman_collection.json\n' "$script_dir"
printf 'Private Postman environment: %s\n' "$environment_file"
printf 'Import both into Postman and run all three folders in order.\n'
