# LIV-136 — JSON success wrapper migration inventory

Current checkout: `feature/LIV-134-s01-auth-implementation`, 2026-11-13. Inventory source: `contracts/openapi/sprint3-route-inventory.json`, S01–S04 OpenAPI candidates, and Java Controller source search.

| Scope | Current state in this checkout | Wrapper action |
|---|---|---|
| S01 API-001 `POST /api/v1/auth/register` | The only S01–S04 Controller implemented in this checkout | Implemented here with `ResponseEntity<ApiResponse<RegisterResponse>>`, HTTP 201, concrete OpenAPI response and PostgreSQL tests |
| S01 remaining 19 candidate operations and 4 security-blocked operations | No Controller implementation found | Decide each JSON success response, update OpenAPI and consumer/tests in its own task. Preserve 204/redirect/protocol exceptions. |
| S02 14 candidate, 3 internal candidate and 1 security-blocked operation | No Controller implementation found | Apply wrapper as endpoints are implemented, with GitHub App protocol responses assessed separately. |
| S03 17 candidate and 3 internal candidate operations | No Controller implementation found | Apply wrapper when each JSON endpoint is implemented. |
| S04 36 candidate, 4 internal candidate, 3 planned and API-120 in progress | No Controller implementation found in this checkout; API-120 is owned by a separate implementation task | Do not alter in LIV-136. Review API-120 and future JSON endpoints with their owners. |

S05 is Python and outside the S01–S04 wrapper decision. Inventory status is contract/catalog status, not proof of a running API. Search for a Controller and run real request tests before marking an operation implemented. Do not wrap HTTP 204, redirects, files/streams or protocol-specific responses; keep errors as RFC 9457 Problem.
