package com.livingdocs.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.livingdocs.model.*;
import com.livingdocs.client.AuthoritativeHeadLookup;
import com.livingdocs.exception.SourceUnavailableException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceValidationServiceTests {
    private static final SourceScope SCOPE = new SourceScope(UUID.randomUUID(), UUID.randomUUID());
    private static final UUID REPO = UUID.randomUUID();
    private static final String SHA = "a".repeat(40);
    private static final String REF = "refs/heads/main";
    private static final String CONFIG = "cfg-3";
    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    // Test fixture duration only, not a deployment policy.
    private static final Duration VALIDITY = Duration.ofSeconds(10);
    private final SourceConnection connection = new SourceConnection(SCOPE, REPO, CONFIG, true);

    private SourceValidationRequest request() {
        return new SourceValidationRequest(REPO, TargetKind.BRANCH, REF, SHA, CONFIG);
    }

    private SourceHead head(String sha) {
        return new SourceHead(SCOPE, REPO, TargetKind.BRANCH, REF, sha, CONFIG, "fence-17");
    }

    private SourceValidationService service(AuthoritativeHeadLookup lookup) {
        return new SourceValidationService((scope, id) -> Optional.of(connection), lookup, CLOCK, VALIDITY);
    }

    private void assertFailure(SourceValidationResult result, ValidationReason reason) {
        assertEquals(ValidationStatus.FAIL, result.validationStatus());
        assertEquals(reason, result.reason());
        assertNotNull(result.validationId());
    }

    @Test
    void currentShaReturnsBoundEvidence() {
        SourceValidationResult result = service((c, k, r) -> Optional.of(head(SHA))).validate(SCOPE, request());
        assertEquals(ValidationStatus.PASS, result.validationStatus());
        assertEquals(ValidationReason.CURRENT, result.reason());
        assertEquals(SCOPE.tenantId(), result.tenantId());
        assertEquals(SCOPE.workspaceId(), result.workspaceId());
        assertEquals(REPO, result.repositoryConnectionId());
        assertEquals(REF, result.targetRef());
        assertEquals(SHA, result.validatedHeadSha());
        assertEquals(CONFIG, result.configRevision());
        assertEquals("fence-17", result.sourceRevision());
        assertEquals(NOW, result.checkedAt());
        assertEquals(NOW.plus(VALIDITY), result.expiresAt());
    }

    @Test
    void staleShaDoesNotReplaceTheExpectedBinding() {
        SourceValidationResult result = service((c, k, r) -> Optional.of(head("b".repeat(40)))).validate(SCOPE, request());
        assertFailure(result, ValidationReason.STALE_SHA);
        assertEquals(SHA, result.expectedHeadSha());
        assertEquals("b".repeat(40), result.validatedHeadSha());
    }

    @Test
    void everyValidationReadsHeadAgainAndHasADistinctAttemptId() {
        AtomicInteger reads = new AtomicInteger();
        SourceValidationService service = service((c, k, r) -> Optional.of(head(reads.getAndIncrement() == 0 ? SHA : "b".repeat(40))));
        SourceValidationResult first = service.validate(SCOPE, request());
        SourceValidationResult second = service.validate(SCOPE, request());
        assertEquals(ValidationStatus.PASS, first.validationStatus());
        assertFailure(second, ValidationReason.STALE_SHA);
        assertNotEquals(first.validationId(), second.validationId());
        assertEquals(2, reads.get());
    }

    @Test
    void unknownConnectionDoesNotCallProvider() {
        SourceValidationService service = new SourceValidationService((s, id) -> Optional.empty(),
                (c, k, r) -> fail("Unknown connections must not reach GitHub"), CLOCK, VALIDITY);
        assertFailure(service.validate(SCOPE, request()), ValidationReason.UNKNOWN_CONNECTION);
    }

    @Test
    void connectionFromAnotherTenantOrWorkspaceIsHidden() {
        for (SourceScope foreign : new SourceScope[]{new SourceScope(UUID.randomUUID(), SCOPE.workspaceId()),
                new SourceScope(SCOPE.tenantId(), UUID.randomUUID())}) {
            SourceValidationService service = new SourceValidationService(
                    (s, id) -> Optional.of(new SourceConnection(foreign, REPO, CONFIG, true)),
                    (c, k, r) -> fail("Out-of-scope connections must not reach GitHub"), CLOCK, VALIDITY);
            assertFailure(service.validate(SCOPE, request()), ValidationReason.UNKNOWN_CONNECTION);
        }
    }

    @Test
    void wrongRepositoryReturnedByLookupIsHidden() {
        SourceValidationService service = new SourceValidationService(
                (s, id) -> Optional.of(new SourceConnection(SCOPE, UUID.randomUUID(), CONFIG, true)),
                (c, k, r) -> fail("Wrong repository"), CLOCK, VALIDITY);
        assertFailure(service.validate(SCOPE, request()), ValidationReason.UNKNOWN_CONNECTION);
    }

    @Test
    void inactiveOrReconfiguredConnectionsDoNotReachProvider() {
        for (SourceConnection c : new SourceConnection[]{new SourceConnection(SCOPE, REPO, CONFIG, false),
                new SourceConnection(SCOPE, REPO, "cfg-4", true)}) {
            SourceValidationService service = new SourceValidationService((s, id) -> Optional.of(c),
                    (ignored, k, r) -> fail("SourceConnection guard must run first"), CLOCK, VALIDITY);
            assertFailure(service.validate(SCOPE, request()), c.active() ? ValidationReason.CONFIG_CHANGED : ValidationReason.CONNECTION_INACTIVE);
        }
    }

    @Test
    void confirmedMissingRefFailsClosed() {
        assertFailure(service((c, k, r) -> Optional.empty()).validate(SCOPE, request()), ValidationReason.UNKNOWN_REF);
    }

    @Test
    void providerOrConnectionStoreFailureDoesNotLeakSecrets() {
        String secret = "Bearer secret-token credential_ref https://private.example";
        SourceValidationService providerFailure = service((c, k, r) -> { throw new SourceUnavailableException(secret); });
        SourceValidationService storeFailure = new SourceValidationService((s, id) -> { throw new DataAccessResourceFailureException(secret); },
                (c, k, r) -> fail("Store failed"), CLOCK, VALIDITY);
        for (SourceValidationService service : new SourceValidationService[]{providerFailure, storeFailure}) {
            SourceValidationResult result = service.validate(SCOPE, request());
            assertFailure(result, ValidationReason.SOURCE_UNAVAILABLE);
            assertNull(result.validatedHeadSha());
            assertNull(result.sourceRevision());
            assertFalse(result.toString().contains(secret));
        }
    }

    @Test
    void unexpectedProgrammingErrorIsNotReportedAsAnUnavailableSource() {
        SourceValidationService service = service((connection, kind, ref) -> {
            throw new IllegalStateException("Unexpected state");
        });

        assertThrows(IllegalStateException.class, () -> service.validate(SCOPE, request()));
    }

    @Test
    void mismatchedProviderScopeRepositoryKindOrRefCannotPassEvenWithMatchingSha() {
        for (SourceHead h : new SourceHead[]{
                new SourceHead(new SourceScope(UUID.randomUUID(), SCOPE.workspaceId()), REPO, TargetKind.BRANCH, REF, SHA, CONFIG, "17"),
                new SourceHead(SCOPE, UUID.randomUUID(), TargetKind.BRANCH, REF, SHA, CONFIG, "17"),
                new SourceHead(SCOPE, REPO, TargetKind.PR, REF, SHA, CONFIG, "17"),
                new SourceHead(SCOPE, REPO, TargetKind.BRANCH, "refs/heads/other", SHA, CONFIG, "17")}) {
            SourceValidationResult result = service((c, k, r) -> Optional.of(h)).validate(SCOPE, request());
            assertFailure(result, ValidationReason.SOURCE_BINDING_MISMATCH);
            assertNull(result.validatedHeadSha());
        }
    }

    @Test
    void configurationChangedDuringProviderReadFailsClosed() {
        SourceHead changed = new SourceHead(SCOPE, REPO, TargetKind.BRANCH, REF, SHA, "cfg-4", "18");
        assertFailure(service((c, k, r) -> Optional.of(changed)).validate(SCOPE, request()), ValidationReason.CONFIG_CHANGED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "g000000000000000000000000000000000000000", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void invalidProviderShaCannotPass(String sha) {
        assertFailure(service((c, k, r) -> Optional.of(head(sha))).validate(SCOPE, request()), ValidationReason.INVALID_SOURCE);
    }

    @Test
    void missingFenceCannotPass() {
        SourceHead missing = new SourceHead(SCOPE, REPO, TargetKind.BRANCH, REF, SHA, CONFIG, " ");
        assertFailure(service((c, k, r) -> Optional.of(missing)).validate(SCOPE, request()), ValidationReason.INVALID_SOURCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "refs/tags/v1", "refs/heads/", "refs/heads/a..b", "refs/heads/.hidden",
            "refs/heads/a.lock", "refs/heads/a b", "refs/heads/a~1", "refs/heads/a//b", "refs/heads/a@{1}"})
    void ambiguousOrInvalidRefIsRejectedBeforeLookup(String ref) {
        SourceValidationService service = new SourceValidationService((s, id) -> fail("Invalid request"),
                (c, k, r) -> fail("Invalid request"), CLOCK, VALIDITY);
        assertFailure(service.validate(SCOPE, new SourceValidationRequest(REPO, TargetKind.BRANCH, ref, SHA, CONFIG)), ValidationReason.INVALID_REQUEST);
    }

    @Test
    void abbreviatedExpectedShaIsNotAccepted() {
        assertFailure(service((c, k, r) -> fail("Invalid request")).validate(SCOPE,
                new SourceValidationRequest(REPO, TargetKind.BRANCH, REF, "aaaaaaa", CONFIG)), ValidationReason.INVALID_REQUEST);
    }

    @Test
    void prUsesExplicitPrNumberAndHeadBinding() {
        SourceHead pr = new SourceHead(SCOPE, REPO, TargetKind.PR, "42", SHA, CONFIG, "17");
        SourceValidationResult result = service((c, kind, ref) -> {
            assertEquals(TargetKind.PR, kind);
            assertEquals("42", ref);
            return Optional.of(pr);
        }).validate(SCOPE, new SourceValidationRequest(REPO, TargetKind.PR, "42", SHA.toUpperCase(), CONFIG));
        assertEquals(ValidationStatus.PASS, result.validationStatus());
    }

    @Test
    void lookupThatUsesEntireValidityWindowCannotReturnPass() {
        Clock advancing = new Clock() {
            private int calls;
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return calls++ == 0 ? NOW : NOW.plus(VALIDITY); }
        };
        SourceValidationService service = new SourceValidationService((s, id) -> Optional.of(connection),
                (c, k, r) -> Optional.of(head(SHA)), advancing, VALIDITY);
        assertFailure(service.validate(SCOPE, request()), ValidationReason.VALIDATION_EXPIRED);
    }

    @Test
    void deploymentMustSupplyPositiveValidity() {
        for (Duration duration : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SourceValidationService((s, id) -> Optional.empty(), (c, k, r) -> Optional.empty(), CLOCK, duration));
        }
    }

    @Test
    void missingCurrentConfigurationIsUnavailableAndDoesNotCallGithub() {
        SourceValidationService service = new SourceValidationService(
                (scope, id) -> Optional.of(new SourceConnection(scope, id, null, true)),
                (connection, kind, ref) -> fail("Không gọi GitHub khi thiếu phiên bản cấu hình"),
                CLOCK, VALIDITY);
        assertFailure(service.validate(SCOPE, request()), ValidationReason.SOURCE_UNAVAILABLE);
    }
}
