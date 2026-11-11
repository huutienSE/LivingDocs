#!/usr/bin/env python3
"""Observable live integration smoke: Keycloak -> Gateway -> two HTTP backends.

Requires Keycloak, stub.py, and real LivingDocs Gateway to be running on host.
Uses only Python standard library. Does not print any bearer token or secrets.
"""
import base64
import json
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent
LOCAL = ROOT / ".local"
LOG = LOCAL / "backend_requests.jsonl"
ISSUER = "http://localhost:8180/realms/livingdocs-demo"
GATEWAY = "http://127.0.0.1:8081"
passed = 0
failed = 0


def env():
    path = LOCAL / "demo.env"
    if not path.exists():
        raise RuntimeError("Run setup.py first")
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            result[k] = v
    return result


def http(url, method="GET", headers=None, body=None):
    req = Request(url, data=body, headers=headers or {}, method=method)
    try:
        with urlopen(req, timeout=9) as response:
            return response.status, dict(response.headers), response.read().decode("utf-8", "replace")
    except HTTPError as exc:
        return exc.code, dict(exc.headers), exc.read().decode("utf-8", "replace")


def count_log():
    if not LOG.exists():
        return 0
    with LOG.open(encoding="utf-8") as f:
        return sum(1 for _ in f)


def assert_step(name, url, method="GET", headers=None, expected_status=200, expected_count=0, expected_service=None):
    global passed, failed
    before = count_log()
    status, response_headers, body = http(url, method=method, headers=headers)
    after = count_log()
    count = after - before
    is_ok = status == expected_status and count == expected_count
    if expected_service:
        is_ok &= response_headers.get("X-Backend-Service", "") == expected_service
        try:
            b = json.loads(body)
            is_ok &= b.get("service") == expected_service
            is_ok &= b.get("correlationId") == (headers or {}).get("X-Correlation-ID")
        except json.JSONDecodeError:
            is_ok = False
    if is_ok:
        passed += 1
        print(f"PASS {name}: HTTP {status}; backend receptions +{count}")
    else:
        failed += 1
        print(f"FAIL {name}: got HTTP {status} (wanted {expected_status}); backend receptions +{count} (wanted +{expected_count})")
        print("  headers:", {key: value for key, value in response_headers.items() if key.lower() in ("x-backend-service", "x-correlation-id", "access-control-allow-origin")})
        print("  body:", body[:240])


def decode_claims(token):
    encoded = token.split(".")[1]
    raw = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
    return json.loads(raw.decode("utf-8"))


def main():
    try:
        config = env()
        status, _, metadata_text = http(ISSUER + "/.well-known/openid-configuration")
        if status != 200:
            raise RuntimeError(f"Keycloak OIDC metadata returned HTTP {status}")
        meta = json.loads(metadata_text)
        if meta.get("issuer") != ISSUER:
            raise RuntimeError(f"Issuer mismatch: {meta.get('issuer')!r} != {ISSUER!r}")
        jwks_code, _, jwks_text = http(meta["jwks_uri"])
        if jwks_code != 200 or not json.loads(jwks_text).get("keys"):
            raise RuntimeError("OIDC JWKS unavailable/empty")
        print("PASS Keycloak: correct OIDC issuer and non-empty JWKS")

        params = urlencode({
            "grant_type": "client_credentials",
            "client_id": "livingdocs-demo-issuer-client",
            "client_secret": config["DEMO_CLIENT_SECRET"],
        }).encode("utf-8")
        status, _, body = http(ISSUER + "/protocol/openid-connect/token", method="POST", headers={"Content-Type": "application/x-www-form-urlencoded"}, body=params)
        if status != 200:
            raise RuntimeError(f"Keycloak token endpoint: HTTP {status}: {body[:350]}")
        token = json.loads(body)["access_token"]
        claims = decode_claims(token)
        print("Token claims (for illustration ONLY; real validation runs in Spring Security):")
        print("  iss:", claims.get("iss"), "aud:", claims.get("aud"), "exp:", claims.get("exp"))
        aud = claims.get("aud", [])
        if isinstance(aud, str):
            aud = [aud]
        if claims.get("iss") != ISSUER or "livingdocs-gateway" not in aud:
            raise RuntimeError("JWT issuer/audience does not match Gateway config; check realm mapper")
        print("PASS Token: issuer and audience claims present")

        bad_parts = token.split(".")
        sig = bad_parts[-1]
        bad_parts[-1] = ("A" if sig[0] != "A" else "B") + sig[1:]
        tampered = ".".join(bad_parts)

        assert_step("No token -> reject", GATEWAY + "/api/v1/review-queue", expected_status=401)
        assert_step("Tampered signature -> reject", GATEWAY + "/api/v1/review-queue", headers={"Authorization": "Bearer " + tampered}, expected_status=401)
        assert_step("Valid token -> S01", GATEWAY + "/api/v1/users/me", headers={"Authorization": "Bearer " + token, "X-Correlation-ID": "demo-s01-001"}, expected_status=200, expected_count=1, expected_service="S01-DEMO")
        assert_step("Valid token -> S04", GATEWAY + "/api/v1/review-queue", headers={"Authorization": "Bearer " + token, "X-Correlation-ID": "demo-s04-001"}, expected_status=200, expected_count=1, expected_service="S04-DEMO")
        assert_step("Internal path -> denyAll", GATEWAY + "/internal/v1/demo", headers={"Authorization": "Bearer " + token}, expected_status=403)
        assert_step("Unknown path -> no route", GATEWAY + "/api/v1/this-path-is-not-allowlisted", headers={"Authorization": "Bearer " + token}, expected_status=404)
        assert_step("Wrong method -> no route", GATEWAY + "/api/v1/review-queue", method="POST", headers={"Authorization": "Bearer " + token}, expected_status=404)
        assert_step("CORS valid preflight", GATEWAY + "/api/v1/review-queue", method="OPTIONS", headers={"Origin": "http://localhost:3000", "Access-Control-Request-Method": "GET", "Access-Control-Request-Headers": "Authorization,If-Match"}, expected_status=200)
        assert_step("CORS invalid origin", GATEWAY + "/api/v1/review-queue", method="OPTIONS", headers={"Origin": "http://not-allowed.example", "Access-Control-Request-Method": "GET"}, expected_status=403)
    except (RuntimeError, KeyError, IndexError, ValueError, URLError, OSError) as exc:
        print("SETUP FAILURE:", str(exc))
        print("Check Keycloak :8180, Gateway :8081, backend stubs :18082/:18084 and their terminal logs.")
        return 2
    print(f"\nRESULT: {passed} passed; {failed} failed")
    if failed:
        print("Inspect Gateway startup/security logs and demo/gateway-live-v1/.local/backend_requests.jsonl")
        return 1
    print("LIVE GATEWAY EVIDENCE PASS: JWT validated, routes selected, backend receipts observed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
