# LivingDocs — Gateway JWT / Routing live demo v1

**Scope:** isolated **local-only** demo on branch `demo-v1`. Nothing changes in `infrastructure/api-gateway` source. No real S01/S04 controllers are required: two Python test backends are used. The Gateway itself is the real Spring WebFlux app from LivingDocs.

**Ports:** Keycloak 8180, Gateway 8081, management 9091, stub S01 18082, stub S04 18084.

## Prerequisites

macOS with Docker Desktop running, Python 3 and Java 21. Run commands from the **LivingDocs repo root**. Confirm `git branch --show-current` says `demo-v1` before copying these files.

## 1 — Prepare and launch local JWT issuer

```bash
python3 demo/gateway-live-v1/setup.py
docker compose --env-file demo/gateway-live-v1/.local/demo.env -f demo/gateway-live-v1/compose.yaml up -d
docker compose --env-file demo/gateway-live-v1/.local/demo.env -f demo/gateway-live-v1/compose.yaml logs --tail=40 keycloak
curl -i http://localhost:8180/realms/livingdocs-demo/.well-known/openid-configuration
```

The metadata endpoint must return HTTP 200 and `issuer: http://localhost:8180/realms/livingdocs-demo`. Keycloak may need some time to finish its initial realm import. Retry the `curl` only after logs show it started.

To open admin console for troubleshooting: http://localhost:8180/admin; username `admin`; locally generated password in `.local/demo.env`. **Do not paste the password into chat or commit the file.**

## 2 — Start BOTH test backends (Terminal A)

From the repo root:

```bash
python3 demo/gateway-live-v1/stub.py
```

Leave running. Must print `READY S01-DEMO :18082` and `READY S04-DEMO :18084`. Receipts are appended to `.local/backend_requests.jsonl`.

## 3 — Start the real LivingDocs Gateway (Terminal B)

From the repo root:

```bash
cd infrastructure/api-gateway
JWT_ISSUER_URI=http://localhost:8180/realms/livingdocs-demo \
JWT_AUDIENCE=livingdocs-gateway \
S01_SERVICE_URI=http://127.0.0.1:18082 \
S04_SERVICE_URI=http://127.0.0.1:18084 \
./mvnw spring-boot:run
```

Leave running. This uses **environment overrides**, not edits to the normal Gateway `.env` or application YAML. Gateway remains on port `8081`; requires Java 21. To sanity-check Gateway itself, from another terminal:

```bash
curl -i http://127.0.0.1:9091/actuator/health
```

Do NOT start a second Gateway container on 8081. Make sure any existing process on 8081 is stopped first.

## 4 — Run the integrated test battery (Terminal C)

From repo root:

```bash
python3 demo/gateway-live-v1/smoke.py
```

Expected: Keycloak metadata/JWKS and audience claims PASS, then tests for no token 401, altered signature 401, successful S01/S04 response with backend receipts, forbidden internal path 403, unknown/mismatched route 404, allowed and denied CORS. The log verification is the key: rejected requests do not increase backend count; valid requests each increase it by one.

View recorded backend receipts:

```bash
tail -n 20 demo/gateway-live-v1/.local/backend_requests.jsonl
```

**Interpretation:** This proves JWT authentication at Gateway + allowlist routing + HTTP forwarding for the real Gateway build, using test backends. It does NOT prove S01/S04 business endpoints, S01 JWT issuance, tenant authorization, Kafka, or E2E business workflow.

## Troubleshooting

- **Keycloak `curl` 404 or cannot connect:** confirm Docker Desktop, `docker compose ... ps`, `logs -f`, and port 8180 free. Realm is imported only on initial creation; if you previously ran a different Keycloak at that port, stop it.
- **JWT `aud` absent:** verify demo realm audience mapper in Keycloak; existing realms are NOT reimported automatically on startup.
- **Gateway fails to start:** check port 8081, JDK 21, the Keycloak issuer and `.env` import. If you already run Gateway on your Mac, stop it before demo.
- **Valid token still gets `401`:** inspect `iss`/`aud`; ensure Keycloak metadata issuer exactly matches `JWT_ISSUER_URI`. Gateway and Keycloak must both be reachable on localhost.
- **Valid token gets `404` or `503`:** make sure stub.py is running and `S01_SERVICE_URI`/`S04_SERVICE_URI` overrides reached Gateway process; preserve real Gateway `application.yaml` routes.
- **Port 9091 unavailable:** check management server startup logs. This demo runs Gateway on host, avoiding the known Compose management loopback mismatch.
- **CORS returns 204 instead of 200:** Spring versions may differ in preflight success code; check Access-Control-Allow-Origin and no backend receipt. The script currently expects 200 and can be adjusted if the runtime returns 204.

Stop by Ctrl+C in stub and Gateway terminals. Stop only this demo Keycloak:

```bash
docker compose --env-file demo/gateway-live-v1/.local/demo.env -f demo/gateway-live-v1/compose.yaml down
```

**Do not run `docker compose down -v` on your main LivingDocs Compose environment.**
