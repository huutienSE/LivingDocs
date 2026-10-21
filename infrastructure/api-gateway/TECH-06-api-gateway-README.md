# LivingDocs API Gateway — Multi-Service Proof of Concept (TECH-06)

> **Scope:** TECH-06 / [LIV-60](https://huutiense25.atlassian.net/browse/LIV-60).  
> **Status:** Local proof of concept; not the approved LivingDocs production architecture.

## Purpose

Run **Spring Cloud Gateway Server WebFlux** with two independent Spring Boot MVC demo downstream services and verify route forwarding, application health, and basic failure behavior. Service A and Service B are temporary technical demos ([LIV-53](https://huutiense25.atlassian.net/browse/LIV-53) and [LIV-58](https://huutiense25.atlassian.net/browse/LIV-58)); they do not represent final LivingDocs business service boundaries.

## Prerequisites

- Java **21** installed and selected (`java -version`).
- Git working tree containing merged Demo Service A, Demo Service B, and TECH-06 Gateway route changes.
- `curl` and an available local port **8081**, **8082**, **8083**.
- Maven Wrapper (`./mvnw`) in each application directory. No global Maven installation is required.

## Applications and routes

| Application | Working directory | Local port | API / route |
| --- | --- | ---: | --- |
| API Gateway | `infrastructure/api-gateway` | 8081 | `/service-a/**`, `/service-b/**`; existing `/backend/**` route retained |
| Demo Service A | `backend/demo-service-a` | 8082 | `GET /api/identity` → `{"service":"A"}` |
| Demo Service B | `backend/demo-service-b` | 8083 | `GET /api/identity` → `{"service":"B"}` |

Gateway route configuration is in `infrastructure/api-gateway/src/main/resources/application.yaml`.

Expected TECH-06 route definitions:

```yaml
- id: demo-service-a
  uri: ${DEMO_SERVICE_A_URI:http://127.0.0.1:8082}
  predicates:
    - Path=/service-a/**
  filters:
    - StripPrefix=1

- id: demo-service-b
  uri: ${DEMO_SERVICE_B_URI:http://127.0.0.1:8083}
  predicates:
    - Path=/service-b/**
  filters:
    - StripPrefix=1
```

For instance, `GET /service-a/api/identity` matches the `demo-service-a` route; `StripPrefix=1` removes `/service-a`, and the Gateway forwards `GET /api/identity` to `http://127.0.0.1:8082`.

## Local startup — three terminals

Run each command from **the repository root** in a separate terminal. Keep all three processes running during verification.

**Terminal 1 — Service A**

```bash
cd backend/demo-service-a
./mvnw spring-boot:run
```

**Terminal 2 — Service B**

```bash
cd backend/demo-service-b
./mvnw spring-boot:run
```

**Terminal 3 — API Gateway**

```bash
cd infrastructure/api-gateway
./mvnw spring-boot:run
```

For a clean build, run `./mvnw clean verify` within each application directory before starting that application.

> The retained `/backend/**` Gateway route targets `http://127.0.0.1:8080`. The Spring Boot baseline at port 8080 is **optional for the two-service TECH-06 tests**; it must be started separately if you want to exercise that legacy TECH-05 route.

## Verification — fourth terminal

```bash
# Verify each downstream directly
curl -i http://127.0.0.1:8082/api/identity
curl -i http://127.0.0.1:8083/api/identity

# Verify forwarding through the Gateway
curl -i http://127.0.0.1:8081/service-a/api/identity
curl -i http://127.0.0.1:8081/service-b/api/identity

# Verify health and loaded routes
curl -i http://127.0.0.1:8081/actuator/health
curl -i http://127.0.0.1:8082/actuator/health
curl -i http://127.0.0.1:8083/actuator/health
curl -i http://127.0.0.1:8081/actuator/gateway/routes
```

With healthy downstream services, both Gateway calls should return HTTP 200 with distinguishable JSON bodies `{"service":"A"}` and `{"service":"B"}`. The health endpoints should report `"status":"UP"`. The Gateway route listing should contain `demo-service-a` (matching `/service-a/**`) and `demo-service-b` (matching `/service-b/**`).

## Basic failure and recovery checks

1. With all three applications running, call `curl -i http://127.0.0.1:8081/invalid/api/test`. Record the actual HTTP status (normally 404 when no route matches).
2. Stop **only Demo Service B** with `Ctrl+C` in its terminal.
3. Call `curl -i http://127.0.0.1:8081/service-b/api/identity`. Record the actual error HTTP status and, if applicable, Gateway logs; do not assume a specific error status.
4. Confirm Service A is unaffected: `curl -i http://127.0.0.1:8081/service-a/api/identity` should still return HTTP 200 with `{"service":"A"}`.
5. Confirm Gateway health still responds: `curl -i http://127.0.0.1:8081/actuator/health`.
6. Restart Demo Service B, then verify `curl -i http://127.0.0.1:8081/service-b/api/identity` returns HTTP 200 with `{"service":"B"}`.

Gateway `health: UP` alone **does not prove downstream availability**. See the linked [TECH-06 evidence report](../../docs/technical-spikes/TECH-06-api-gateway-poc.md).

## Configuration and caveats

- Use `DEMO_SERVICE_A_URI` or `DEMO_SERVICE_B_URI` environment variables to override local downstream addresses when appropriate.
- The Gateway binds to `127.0.0.1` in the current local configuration; containerized deployment will require environment-specific network configuration.
- The Actuator Gateway route endpoint is configured for local read-only visibility; restrict operational endpoints in any non-local environment.
- This PoC **does not** validate authentication, authorization, production fault handling, performance, high availability, or final service decomposition. Those require separately scoped design and verification.
