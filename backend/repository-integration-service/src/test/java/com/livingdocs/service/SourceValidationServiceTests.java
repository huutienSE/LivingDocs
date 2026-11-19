package com.livingdocs.service;

import com.livingdocs.client.GitHubSourceClient.CommitObservation;
import com.livingdocs.entity.SourceTargetHeadEntity;
import com.livingdocs.entity.SourceTargetHeadEntity.HeadState;
import com.livingdocs.entity.SourceTargetKey;
import com.livingdocs.exception.SourceUnavailableException;
import com.livingdocs.model.SourceConnection;
import com.livingdocs.model.SourceScope;
import com.livingdocs.model.SourceValidationRequest;
import com.livingdocs.model.SourceValidationResult;
import com.livingdocs.model.TargetKind;
import com.livingdocs.model.ValidationReason;
import com.livingdocs.model.ValidationStatus;
import com.livingdocs.repository.JpaSourceObservationStore;
import com.livingdocs.repository.JpaSourceObservationStore.WriteResult;
import com.livingdocs.service.GitHubSourceLookup.Outcome;
import com.livingdocs.service.GitHubSourceLookup.Result;
import com.livingdocs.service.impl.SourceValidationServiceImpl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SourceValidationServiceTests {
    private static final SourceScope SCOPE = new SourceScope(UUID.randomUUID(), UUID.randomUUID());
    private static final UUID REPO = UUID.randomUUID();
    private static final String SHA = "a".repeat(40);
    private static final String REF = "refs/heads/main";
    private static final String CONFIG = "cfg-3";
    private static final String AUTHORITY = "123456";
    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration VALIDITY = Duration.ofSeconds(10);

    private final GitHubSourceLookup github = mock(GitHubSourceLookup.class);
    private final JpaSourceObservationStore store = mock(JpaSourceObservationStore.class);
    private final SourceValidationService service = new SourceValidationServiceImpl(github, store, CLOCK, VALIDITY);

    @Test
    void currentShaReturnsBoundEvidenceFromTheCommittedHead() {
        stubObserved(request(), commit(123456L, 123456L, SHA));
        stubAccepted(request());
        SourceValidationResult result = service.validate(SCOPE, request());

        assertEquals(ValidationStatus.PASS, result.validationStatus());
        assertEquals(ValidationReason.CURRENT, result.reason());
        assertEquals(SCOPE.tenantId(), result.tenantId());
        assertEquals(SCOPE.workspaceId(), result.workspaceId());
        assertEquals(REPO, result.repositoryConnectionId());
        assertEquals(REF, result.targetRef());
        assertEquals(SHA, result.validatedHeadSha());
        assertEquals(CONFIG, result.configRevision());
        assertEquals("17", result.sourceRevision());
        assertEquals(NOW, result.checkedAt());
        assertEquals(NOW.plus(VALIDITY), result.expiresAt());
    }

    @ParameterizedTest
    @CsvSource({
            "INVALID_REQUEST, INVALID_REQUEST", "UNKNOWN_CONNECTION, UNKNOWN_CONNECTION",
            "CONNECTION_INACTIVE, CONNECTION_INACTIVE", "CONFIG_CHANGED, CONFIG_CHANGED",
            "UNKNOWN_REF, UNKNOWN_REF", "SOURCE_UNAVAILABLE, SOURCE_UNAVAILABLE"
    })
    void rejectedReadDoesNotWriteAnObservation(Outcome outcome, ValidationReason reason) {
        when(github.readCurrent(SCOPE, REPO, TargetKind.BRANCH, REF, CONFIG))
                .thenReturn(new Result(outcome, null, null, null, 0));
        assertFailure(service.validate(SCOPE, request()), reason);
        verifyNoInteractions(store);
    }

    @Test
    void wrongScopeOrConnectionInObservationCannotPass() {
        var commit = commit(123456L, 123456L, SHA);
        for (SourceConnection connection : new SourceConnection[]{
                new SourceConnection(new SourceScope(UUID.randomUUID(), SCOPE.workspaceId()), REPO, CONFIG, true),
                new SourceConnection(new SourceScope(SCOPE.tenantId(), UUID.randomUUID()), REPO, CONFIG, true),
                new SourceConnection(SCOPE, UUID.randomUUID(), CONFIG, true)}) {
            when(github.readCurrent(SCOPE, REPO, TargetKind.BRANCH, REF, CONFIG))
                    .thenReturn(new Result(Outcome.OBSERVED, connection, commit, AUTHORITY, 6));
            assertFailure(service.validate(SCOPE, request()), ValidationReason.SOURCE_BINDING_MISMATCH);
        }
        verifyNoInteractions(store);
    }

    @Test
    void branchCommitFromAnotherRepositoryCannotPass() {
        stubObserved(request(), commit(123456L, 123457L, SHA));
        assertFailure(service.validate(SCOPE, request()), ValidationReason.SOURCE_BINDING_MISMATCH);
        verifyNoInteractions(store);
    }

    @Test
    void prCanUseAnAuthorizedForkAndAcceptUppercaseExpectedSha() {
        var request = new SourceValidationRequest(REPO, TargetKind.PR, "42", SHA.toUpperCase(), CONFIG);
        stubObserved(request, commit(123456L, 123457L, SHA));
        stubAccepted(request);
        assertEquals(ValidationReason.CURRENT, service.validate(SCOPE, request).reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "g000000000000000000000000000000000000000",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "0000000000000000000000000000000000000000"})
    void invalidProviderShaCannotBeWritten(String sha) {
        stubObserved(request(), commit(123456L, 123456L, sha));
        assertFailure(service.validate(SCOPE, request()), ValidationReason.INVALID_SOURCE);
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "refs/tags/v1", "refs/heads/", "refs/heads/a..b", "refs/heads/.hidden",
            "refs/heads/a.lock", "refs/heads/a b", "refs/heads/a~1", "refs/heads/a//b", "refs/heads/a@{1}"})
    void ambiguousOrInvalidRefIsRejectedBeforeLookup(String ref) {
        assertFailure(service.validate(SCOPE,
                new SourceValidationRequest(REPO, TargetKind.BRANCH, ref, SHA, CONFIG)), ValidationReason.INVALID_REQUEST);
        verifyNoInteractions(github, store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aaaaaaa", "0000000000000000000000000000000000000000"})
    void invalidExpectedShaIsRejectedBeforeLookup(String sha) {
        assertFailure(service.validate(SCOPE,
                new SourceValidationRequest(REPO, TargetKind.BRANCH, REF, sha, CONFIG)), ValidationReason.INVALID_REQUEST);
        verifyNoInteractions(github, store);
    }

    @Test
    void providerAndWriteFailuresProduceNoEvidenceOrSecrets() {
        String secret = "Bearer secret-token credential_ref https://private.example";
        when(github.readCurrent(SCOPE, REPO, TargetKind.BRANCH, REF, CONFIG))
                .thenThrow(new SourceUnavailableException(secret));
        assertUnavailable(service.validate(SCOPE, request()), secret);

        org.mockito.Mockito.reset(github);
        stubObserved(request(), commit(123456L, 123456L, SHA));
        when(store.writeObservation(any(), eq(AUTHORITY), eq(CONFIG), eq(6L), any()))
                .thenThrow(new DataAccessResourceFailureException(secret));
        assertUnavailable(service.validate(SCOPE, request()), secret);
    }

    @Test
    void unexpectedProgrammingErrorIsNotReportedAsAnUnavailableSource() {
        when(github.readCurrent(SCOPE, REPO, TargetKind.BRANCH, REF, CONFIG))
                .thenThrow(new IllegalStateException("Unexpected state"));
        assertThrows(IllegalStateException.class, () -> service.validate(SCOPE, request()));
    }

    @Test
    void expiryAtTheExactBoundaryWithholdsCommittedEvidence() {
        Clock advancing = new Clock() {
            private int calls;
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return calls++ == 0 ? NOW : NOW.plus(VALIDITY); }
        };
        stubObserved(request(), commit(123456L, 123456L, SHA));
        stubAccepted(request());
        var result = new SourceValidationServiceImpl(github, store, advancing, VALIDITY).validate(SCOPE, request());
        assertFailure(result, ValidationReason.VALIDATION_EXPIRED);
        assertNull(result.sourceRevision());
    }

    @Test
    void deploymentMustSupplyPositiveValidity() {
        for (Duration duration : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SourceValidationServiceImpl(github, store, CLOCK, duration));
        }
    }

    private static SourceValidationRequest request() {
        return new SourceValidationRequest(REPO, TargetKind.BRANCH, REF, SHA, CONFIG);
    }

    private void stubObserved(SourceValidationRequest request, CommitObservation commit) {
        when(github.readCurrent(SCOPE, REPO, request.targetKind(), request.targetRef(), CONFIG))
                .thenReturn(new Result(Outcome.OBSERVED, new SourceConnection(SCOPE, REPO, CONFIG, true), commit, AUTHORITY, 6));
    }

    private void stubAccepted(SourceValidationRequest request) {
        var target = new SourceTargetKey(SCOPE, REPO, request.targetKind(), request.targetRef());
        var head = mock(SourceTargetHeadEntity.class);
        when(head.getHeadState()).thenReturn(HeadState.PRESENT);
        when(head.getHeadSha()).thenReturn(SHA);
        when(head.getSourceRevision()).thenReturn(17L);
        var write = mock(WriteResult.class);
        when(write.getOutcome()).thenReturn(JpaSourceObservationStore.Outcome.ACCEPTED);
        when(write.getHead()).thenReturn(head);
        when(store.writeObservation(eq(target), eq(AUTHORITY), eq(CONFIG), eq(6L), any())).thenReturn(write);
    }

    private static CommitObservation commit(long authority, long repository, String sha) {
        var commit = mock(CommitObservation.class);
        when(commit.getQueriedRepositoryId()).thenReturn(authority);
        when(commit.getSourceRepositoryId()).thenReturn(repository);
        when(commit.getSha()).thenReturn(sha);
        return commit;
    }

    private static void assertFailure(SourceValidationResult result, ValidationReason reason) {
        assertEquals(ValidationStatus.FAIL, result.validationStatus());
        assertEquals(reason, result.reason());
        assertNotNull(result.validationId());
    }

    private static void assertUnavailable(SourceValidationResult result, String secret) {
        assertFailure(result, ValidationReason.SOURCE_UNAVAILABLE);
        assertNull(result.validatedHeadSha());
        assertNull(result.sourceRevision());
        assertFalse(result.toString().contains(secret));
    }
}
