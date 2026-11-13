# LivingDocs Backend Engineering Standards v3

**Owner decision, 2026-11-13.** This document supersedes older advice against a business Service interface and the older unwrapped JSON success convention. Existing API contracts migrate per task; LIV-136 changes API-001 only.

## Java service structure

- Every S01–S04 business service uses a `Service` interface and a `service/impl` implementation, including when there is only one implementation. Controllers inject the interface. The implementation owns business rules and the `@Transactional` boundary for each use case.
- Use constructor injection, private fields and selective Lombok. DTOs are classes. Apply Jakarta Bean Validation annotations to request properties and `@Valid` at Controller request boundaries. Apply service method validation only where actual method constraints require it. Do not use `@Data` for entities or password-bearing DTOs.
- Use Spring Data JPA/Hibernate for ordinary persistence. Flyway owns schema changes; use `ddl-auto=validate` and disable Open Session in View. Tenant access must obey the business scope and PostgreSQL RLS rules.
- Use typed business exceptions. A `@RestControllerAdvice` maps MVC failures to RFC 9457 `application/problem+json` with a stable `code` and `correlationId`. Security filter failures need their own handlers when that filter chain is implemented.

## Mapping

- Use MapStruct for Entity/domain ↔ DTO mapping with multiple fields or reuse: `@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)`. A direct constructor is appropriate for a one-field UUID → `RegisterResponse` mapping. Create no empty mapper.
- Mapping must not access a database, hash passwords or decide authorization. Do not expose JPA entities, credentials or password hashes through Controller/API responses.

## HTTP success contract

- Ordinary S01–S04 JSON success bodies use `SuccessResponse<T>` with required `code`, `message` and typed `data`. The service-owned `model.SuccessCode` carries `wireCode` and `defaultMessage`; use `SuccessResponse.of(SuccessCode, data)` and `getWireCode()`, never `enum.name()` as the public code. Do not add `success`/`status` fields or put HTTP status in `SuccessCode`. `ResponseEntity<SuccessResponse<T>>` sets status and headers. API-001 Register returns HTTP 201, `code: "IDENTITY_REGISTERED"`, `message: "Registration completed"`, and `data.identityId` as UUID.
- HTTP 204 has no body. Redirects, files/streams, webhooks and other protocol-specific responses follow their own contracts. Error bodies remain RFC 9457 Problem, never `SuccessResponse`.
- Every implemented API must have concrete request and response body schemas, including typed success `data` and error schemas where applicable. Compare static OpenAPI YAML, runtime Springdoc, Controller DTO and real HTTP tests for method/path/status/media type/fields. Swagger visibility alone does not make an API IMPLEMENTED. `CandidateObject` is not an accepted response schema for an implemented operation; retain the current Docker Swagger setup.
- Do not create a cross-service Java business library solely for the wrapper. Roll out the contract to other operations in their own tasks with consumer compatibility review; do not bulk-refactor S02–S05 in LIV-136.

## LIV-136 Register boundary

Register creates a local credential identity only. It does not issue JWT, create tenant/workspace/role, or link an OAuth provider. Password is encoded once with Spring Security `PasswordEncoder`; duplicate email is identified by PostgreSQL's named unique index, including concurrent requests. Never return or log plaintext password or hash.
