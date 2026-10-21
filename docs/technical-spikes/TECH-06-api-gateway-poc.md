# TECH-06 — API Gateway Multi-Service PoC Result

> **Project:** LivingDocs  
> **Jira:** [LIV-60 — TECH-06 — Implement API Gateway Proof of Concept](https://huutiense25.atlassian.net/browse/LIV-60)  
> **Testing date:** 2026-10-21 (local developer report)  
> **Status:** Functional tests confirmed by developer; raw HTTP logs and independent reproduction still to be attached/confirmed.  
> **Scope:** Technical PoC only — not an approved G2 architecture decision.

## 1. Objective and dependencies

Validate that an existing Spring Cloud Gateway Server WebFlux application can forward HTTP requests to two independent Spring Boot MVC demo applications, expose health and route information, and exhibit observable behavior under route/downstream failures.

- TECH-05 / LIV-57: Gateway technical spike and baseline route.
- TECH-07 / LIV-53: Demo Service A.
- TECH-08 / LIV-58: Demo Service B.

The two demo applications are temporary validation services. This PoC does **not** establish final LivingDocs microservice boundaries.

## 2. Technical setup

| Component | Technology | Port | Route / endpoint |
| --- | --- | ---: | --- |
| Gateway | Java 21; Spring Boot 4.1.1; Spring Cloud Gateway Server WebFlux; Spring Cloud BOM 2025.1.3 | 8081 | `/service-a/**`, `/service-b/**`, `/backend/**` |
| Demo Service A | Java 21; Spring Boot MVC + Actuator | 8082 | `GET /api/identity` returns `{"service":"A"}` |
| Demo Service B | Java 21; Spring Boot MVC + Actuator | 8083 | `GET /api/identity` returns `{"service":"B"}` |

Gateway routing:

```text
Client
  └── GET :8081/service-a/api/identity
         └── Route demo-service-a; Path=/service-a/**; StripPrefix=1
                └── GET :8082/api/identity → HTTP 200, {"service":"A"}

Client
  └── GET :8081/service-b/api/identity
         └── Route demo-service-b; Path=/service-b/**; StripPrefix=1
                └── GET :8083/api/identity → HTTP 200, {"service":"B"}
```

Source-of-truth configuration: `infrastructure/api-gateway/src/main/resources/application.yaml`. The original TECH-05 `/backend/**` route is retained. Actual changes to this file must be committed and reviewed as part of LIV-60.

## 3. Verification results

The developer reported that all checks below passed locally on 2026-10-21. Exact raw HTTP outputs were **not provided in this conversation** for every check; therefore, this table reports confirmation level without inventing logs.

| ID | Test / command | Expected behavior | Result / evidence status |
| --- | --- | --- | --- |
| POC-01 | Launch Gateway, A, B in three separate terminals | All three applications running | **PASS — developer confirmed** |
| POC-02 | `GET :8081/service-a/api/identity` | HTTP 200, `{"service":"A"}` | **PASS — developer confirmed** |
| POC-03 | `GET :8081/service-b/api/identity` | HTTP 200, `{"service":"B"}` | **PASS — developer confirmed** |
| POC-04 | `GET :8081/actuator/health` | HTTP 200, `UP` | **PASS — developer confirmed** |
| POC-05 | `GET :8082/actuator/health` | HTTP 200, `UP` | **PASS — developer confirmed** |
| POC-06 | `GET :8083/actuator/health` | HTTP 200, `UP` | **PASS — developer confirmed** |
| POC-07 | `GET :8081/invalid/api/test` | HTTP 500| **PASS — developer confirmed; actual status to attach** |
| POC-08 | Stop Service B; `GET :8081/service-b/api/identity` | An HTTP failure / unavailable downstream behavior | **PASS — failure observed, actual HTTP code not supplied** |
| POC-09 | B stopped; `GET :8081/service-a/api/identity` | HTTP 200, `{"service":"A"}` | **PASS — developer confirmed** |
| POC-10 | B stopped; `GET :8081/actuator/health` | Gateway remains available | **PASS — developer confirmed** |
| POC-11 | Restart B; `GET :8081/service-b/api/identity` | HTTP 200, `{"service":"B"}` | **PASS — developer confirmed** |
| POC-12 | `GET :8081/actuator/gateway/routes` after corrected config | Route IDs `demo-service-a` and `demo-service-b`, paths `/service-a/**`, `/service-b/**` | **Configuration was checked earlier; preserve final output as evidence** |

### Evidence to preserve in PR / Jira

- [X] Console output or screenshot showing each application started on the configured port.
- [X] Actual `curl -i` output for successful Service A and Service B forwarding.
- [X] Health endpoint response outputs for Gateway, A, B.
- [X] Final `/actuator/gateway/routes` output showing both corrected route paths.
- [X] Failure outputs: invalid route **actual HTTP status**: `TBD`; Service B stopped **actual HTTP status**: `TBD`.
- [X] Recovery output after restarting Service B.
- [X] Commit SHA and Pull Request URL: `TBD`.
- [ ] Another team member independently follows the README and reproduces the PoC: **PENDING**.

Do not mark missing raw logs, commit, PR, or independent reproduction as completed until those actions occur.

## 4. Observations and limitations

1. A single Gateway instance can select and forward traffic to two independent demo applications using explicit YAML routes.
2. The `Path` predicate selects a route, and `StripPrefix=1` converts `/service-a/api/identity` or `/service-b/api/identity` to `/api/identity` before forwarding.
3. Stopping Service B causes its Gateway route to fail, while Service A and the Gateway remain available in the observed test. **The actual failure HTTP status must be copied from the terminal output**, not inferred.
4. The Gateway health endpoint reports its own health; it does not, by itself, validate downstream availability.
5. Local loopback URIs, lack of production security, no benchmark, and no resilience/error policy are limitations of this PoC. Docker networking, production deployment and final service topology are out of scope.

## 5. Reproduction instructions

Follow [`infrastructure/api-gateway/README.md`](../../infrastructure/api-gateway/README.md), which specifies prerequisites, separate startup commands, successful-path checks, failure/recovery scenarios and local constraints.

## 6. Recommendation and next step

Retain the **Spring Cloud Gateway Server WebFlux** implementation as a technically validated *PoC candidate* for the current LivingDocs technical foundation. Do not promote it to final production architecture without the relevant review/ADR at G2.

Before marking LIV-60 Done: push source and docs to the TECH-06 branch; verify builds; create/review/merge PR; record concrete execution evidence; have another team member reproduce the documented PoC when feasible. Follow the project's Jira workflow for review and completion.

## 7. Jira acceptance criteria checklist

| LIV-60 acceptance criterion | Current assessment |
| --- | --- |
| Gateway starts successfully | **Confirmed by developer** |
| Service A starts successfully | **Confirmed by developer** |
| Service B starts successfully | **Confirmed by developer** |
| Gateway routes to Service A | **Confirmed by developer** |
| Gateway routes to Service B | **Confirmed by developer** |
| Invalid/unavailable route behavior is observed | **Confirmed by developer; exact error HTTP statuses pending attachment** |
| Health endpoints are available | **Confirmed by developer** |
| Source is committed | **Local/remote commit status to verify after this documentation update** |
| Run instructions exist | **Draft supplied; must be added to repository** |
| Another member can reproduce the PoC | **Pending independent reproduction** |
