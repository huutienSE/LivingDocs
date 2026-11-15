#!/usr/bin/env bash
set -euo pipefail

# Create a Postman/Newman environment for the local S01 Register suite.
# The collection owns its non-secret sample password; this file only sets baseUrl.
base_url="${S01_BASE_URL:-http://127.0.0.1:8081}"
base_url="${base_url%/}"
case "$base_url" in
  http://127.0.0.1:8081|http://localhost:8081) ;;
  *)
    printf 'Only local S01 on port 8081 is allowed.\n' >&2
    exit 2
    ;;
esac

umask 077
temp_root="${TMPDIR:-/tmp}"
environment_dir="$(mktemp -d "${temp_root%/}/liv136-postman.XXXXXX")"
environment_file="$environment_dir/liv136-register.postman_environment.json"
cat > "$environment_file" <<EOF
{
  "name": "LIV-136 Local S01",
  "values": [
    {
      "key": "baseUrl",
      "value": "$base_url",
      "type": "default",
      "enabled": true
    }
  ],
  "_postman_variable_scope": "environment"
}
EOF

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
printf 'Postman environment: %s\n' "$environment_file"
printf 'Newman command: newman run "%s/liv136-register.postman_collection.json" -e "%s"\n' \
  "$script_dir" "$environment_file"
