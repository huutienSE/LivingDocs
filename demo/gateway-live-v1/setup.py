#!/usr/bin/env python3
"""Generate isolated demo-only Keycloak realm and random local secrets.

Never use these accounts or HTTP endpoints for staging/production.
"""
import json
import os
import secrets
from pathlib import Path

ROOT = Path(__file__).resolve().parent
LOCAL = ROOT / ".local"
IMPORT = LOCAL / "import"
ENV_PATH = LOCAL / "demo.env"
REALM_PATH = IMPORT / "livingdocs-demo-realm.json"


def get_env(path):
    result = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line and not line.lstrip().startswith("#") and "=" in line:
                key, val = line.split("=", 1)
                result[key.strip()] = val.strip()
    return result


def main():
    IMPORT.mkdir(parents=True, exist_ok=True)
    settings = get_env(ENV_PATH)
    if not settings:
        settings = {
            "KC_BOOTSTRAP_ADMIN_PASSWORD": secrets.token_urlsafe(22),
            "DEMO_CLIENT_SECRET": secrets.token_urlsafe(30),
        }
        ENV_PATH.write_text(
            "# Demo-only, generated locally. Never commit this file.\n"
            + "\n".join(f"{key}={value}" for key, value in settings.items()) + "\n",
            encoding="utf-8",
        )
        os.chmod(ENV_PATH, 0o600)
    if not settings.get("KC_BOOTSTRAP_ADMIN_PASSWORD") or not settings.get("DEMO_CLIENT_SECRET"):
        raise SystemExit("demo.env is missing required settings")

    realm = {
        "realm": "livingdocs-demo",
        "enabled": True,
        "accessTokenLifespan": 600,
        "clients": [
            {
                "clientId": "livingdocs-demo-issuer-client",
                "name": "Local API Gateway JWT demo client — NOT S01 login",
                "enabled": True,
                "clientAuthenticatorType": "client-secret",
                "secret": settings["DEMO_CLIENT_SECRET"],
                "publicClient": False,
                "bearerOnly": False,
                "standardFlowEnabled": False,
                "directAccessGrantsEnabled": False,
                "serviceAccountsEnabled": True,
                "protocol": "openid-connect",
                "fullScopeAllowed": False,
                "protocolMappers": [
                    {
                        "name": "livingdocs-gateway-audience",
                        "protocol": "openid-connect",
                        "protocolMapper": "oidc-audience-mapper",
                        "consentRequired": False,
                        "config": {
                            "included.custom.audience": "livingdocs-gateway",
                            "access.token.claim": "true",
                            "id.token.claim": "false",
                        },
                    }
                ],
            }
        ],
    }
    if not REALM_PATH.exists():
        REALM_PATH.write_text(json.dumps(realm, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        os.chmod(REALM_PATH, 0o600)
    print("OK: isolated Keycloak realm and demo secrets prepared under demo/gateway-live-v1/.local/")
    print("The client credentials remain on your computer. No real LivingDocs user data is created.")


if __name__ == "__main__":
    main()
