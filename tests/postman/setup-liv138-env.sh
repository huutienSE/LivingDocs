#!/usr/bin/env bash
set -euo pipefail

mode="${1:---baseline}"
if [[ "$mode" != "--baseline" && "$mode" != "--provisioned" || $# -gt 1 ]]; then
  printf 'Usage: %s [--baseline|--provisioned]\n' "$0" >&2; exit 2
fi

base_url="${S01_BASE_URL:-http://127.0.0.1:8081}"
base_url="${base_url%/}"
if [[ ! "$base_url" =~ ^http://(127\.0\.0\.1|localhost):([0-9]{2,5})$ ]] ||
   (( 10#${BASH_REMATCH[2]:-0} > 65535 )); then
  printf 'Only local S01 HTTP URLs are allowed.\n' >&2; exit 2
fi
gateway_url="${GATEWAY_BASE_URL:-http://127.0.0.1:8080}"
gateway_url="${gateway_url%/}"
if [[ ! "$gateway_url" =~ ^http://(127\.0\.0\.1|localhost):([0-9]{2,5})$ ]] ||
   (( 10#${BASH_REMATCH[2]:-0} > 65535 )); then
  printf 'Only local Gateway HTTP URLs are allowed.\n' >&2; exit 2
fi

umask 077
temp_root="${TMPDIR:-/tmp}"
environment_dir="$(mktemp -d "${temp_root%/}/liv138-postman.XXXXXX")"
environment_file="$environment_dir/liv138-access-context.postman_environment.json"

provisioned_email=""
provisioned_password=""
tenant_id=""
review_workspace_id=""
other_workspace_id=""
pending_repository_id=""

if [[ "$mode" == "--provisioned" ]]; then
  provisioned_email="${LIV138_PROVISIONED_EMAIL:-}"
  provisioned_password="${LIV138_PROVISIONED_PASSWORD:-}"
  tenant_id="${LIV138_TENANT_ID:-}"
  review_workspace_id="${LIV138_REVIEW_WORKSPACE_ID:-}"
  other_workspace_id="${LIV138_OTHER_WORKSPACE_ID:-}"
  pending_repository_id="${LIV138_PENDING_REPOSITORY_ID:-}"
  [[ -n "$provisioned_email" ]] || read -r -p 'Provisioned Identity C email: ' provisioned_email
  if [[ -z "$provisioned_password" ]]; then
    read -r -s -p 'Provisioned Identity C password: ' provisioned_password
    printf '\n'
  fi
  [[ -n "$tenant_id" ]] || read -r -p 'Tenant UUID: ' tenant_id
  [[ -n "$review_workspace_id" ]] || read -r -p 'Review Workspace UUID: ' review_workspace_id
  [[ -n "$other_workspace_id" ]] || read -r -p 'Other Workspace UUID: ' other_workspace_id
  [[ -n "$pending_repository_id" ]] || read -r -p 'PENDING Repository UUID: ' pending_repository_id
  uuid_regex='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
  if [[ -z "$provisioned_email" || -z "$provisioned_password" ||
        ! "$tenant_id" =~ $uuid_regex || ! "$review_workspace_id" =~ $uuid_regex ||
        ! "$other_workspace_id" =~ $uuid_regex || ! "$pending_repository_id" =~ $uuid_regex ||
        "$review_workspace_id" == "$other_workspace_id" ]]; then
    printf 'Provisioned fixture requires credentials and distinct valid scope UUIDs.\n' >&2; exit 2
  fi
fi

LIV138_BASE_URL="$base_url" LIV138_GATEWAY_URL="$gateway_url" \
LIV138_RUN_PROVISIONED="$([[ "$mode" == "--provisioned" ]] && printf true || printf false)" \
LIV138_FIXTURE_EMAIL="$provisioned_email" LIV138_FIXTURE_PASSWORD="$provisioned_password" \
LIV138_FIXTURE_TENANT="$tenant_id" LIV138_FIXTURE_REVIEW_WORKSPACE="$review_workspace_id" \
LIV138_FIXTURE_OTHER_WORKSPACE="$other_workspace_id" LIV138_FIXTURE_REPOSITORY="$pending_repository_id" \
python3 - "$environment_file" <<'PY'
import json
import os
import sys
from pathlib import Path

fields = {
    'baseUrl': os.environ['LIV138_BASE_URL'],
    'gatewayUrl': os.environ['LIV138_GATEWAY_URL'],
    'runProvisioned': os.environ['LIV138_RUN_PROVISIONED'],
    'runRevoked': 'false',
    'runGateway': 'false',
    'runExpired': 'false',
    'provisionedEmail': os.environ['LIV138_FIXTURE_EMAIL'],
    'provisionedPassword': os.environ['LIV138_FIXTURE_PASSWORD'],
    'expectedTenantId': os.environ['LIV138_FIXTURE_TENANT'],
    'expectedReviewWorkspaceId': os.environ['LIV138_FIXTURE_REVIEW_WORKSPACE'],
    'expectedOtherWorkspaceId': os.environ['LIV138_FIXTURE_OTHER_WORKSPACE'],
    'pendingRepositoryId': os.environ['LIV138_FIXTURE_REPOSITORY'],
}
document = {
    'name': 'LIV-138 Local S01',
    'values': [
        {'key': key, 'value': value, 'type': 'default', 'enabled': True}
        for key, value in fields.items()
    ],
    '_postman_variable_scope': 'environment',
}
Path(sys.argv[1]).write_text(json.dumps(document, indent=2) + '\n')
PY
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
printf 'Postman environment: %s\n' "$environment_file"
printf 'Newman command: npx --yes newman run "%s/liv138-access-context.postman_collection.json" -e "%s" --reporters cli\n' \
  "$script_dir" "$environment_file"
if [[ "$mode" == "--provisioned" ]]; then
  printf 'Provisioned requests 08/09 enabled with private fixture; revoked/Gateway/expired remain skipped.\n'
else
  printf 'Baseline only: requests 08/09 and dependency-based folders remain skipped.\n'
fi
