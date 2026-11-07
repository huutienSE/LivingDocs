package com.livingdocs.document;

import com.livingdocs.document.domain.*;
import com.livingdocs.document.service.SourceReviewGuard;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SourceReviewGuardTest {
    private final DocumentVersion version = new DocumentVersion(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), "a".repeat(40), DocumentState.IN_REVIEW, ReviewStage.STAFF_REVIEW, 7);
    private final Instant now = Instant.parse("2026-11-06T16:00:00Z");

    private SourceReviewValidation receipt(UUID tenant, UUID workspace, UUID versionId, long revision, UUID repository,
                                          String sha, Instant checked, Instant expires) {
        return new SourceReviewValidation("validation-1", tenant, workspace, versionId, revision, repository, sha, checked, expires);
    }

    private SourceReviewValidation valid() {
        return receipt(version.tenantId(), version.workspaceId(), version.id(), version.revision(), version.repositoryConnectionId(),
                version.sourceSha(), now.minusSeconds(1), now.plusSeconds(60));
    }

    private void unavailable(SourceReviewValidation receipt) {
        assertThatThrownBy(() -> receipt.requireMatches(version, now)).isInstanceOfSatisfying(ReviewException.class, e -> {
            assertThat(e.status()).isEqualTo(503);
            assertThat(e.code()).isEqualTo("SOURCE_VALIDATION_UNAVAILABLE");
        });
    }

    @Test
    void matchingValidation_isAccepted() { assertThatCode(() -> valid().requireMatches(version, now)).doesNotThrowAnyException(); }

    @Test
    void foreignScopeVersionAndRevision_areRejected() {
        var r = valid();
        for (var foreign : List.of(
                receipt(UUID.randomUUID(), r.workspaceId(), r.versionId(), r.versionRevision(), r.repositoryConnectionId(), r.validatedHeadSha(), r.checkedAt(), r.expiresAt()),
                receipt(r.tenantId(), UUID.randomUUID(), r.versionId(), r.versionRevision(), r.repositoryConnectionId(), r.validatedHeadSha(), r.checkedAt(), r.expiresAt()),
                receipt(r.tenantId(), r.workspaceId(), UUID.randomUUID(), r.versionRevision(), r.repositoryConnectionId(), r.validatedHeadSha(), r.checkedAt(), r.expiresAt()),
                receipt(r.tenantId(), r.workspaceId(), r.versionId(), 6, r.repositoryConnectionId(), r.validatedHeadSha(), r.checkedAt(), r.expiresAt()),
                receipt(r.tenantId(), r.workspaceId(), r.versionId(), r.versionRevision(), UUID.randomUUID(), r.validatedHeadSha(), r.checkedAt(), r.expiresAt()))) {
            unavailable(foreign);
        }
    }

    @Test
    void unknownExpiredAndFutureValidation_areRejected() {
        var r = valid();
        for (Instant expires : Arrays.asList(null, now, now.minusSeconds(1))) {
            unavailable(receipt(r.tenantId(), r.workspaceId(), r.versionId(), r.versionRevision(), r.repositoryConnectionId(), r.validatedHeadSha(), r.checkedAt(), expires));
        }
        for (Instant checked : Arrays.asList(null, now.plusSeconds(1))) {
            unavailable(receipt(r.tenantId(), r.workspaceId(), r.versionId(), r.versionRevision(), r.repositoryConnectionId(), r.validatedHeadSha(), checked, r.expiresAt()));
        }
        unavailable(receipt(r.tenantId(), r.workspaceId(), r.versionId(), r.versionRevision(), r.repositoryConnectionId(), null, r.checkedAt(), r.expiresAt()));
        unavailable(new SourceReviewValidation(" ", r.tenantId(), r.workspaceId(), r.versionId(), r.versionRevision(),
                r.repositoryConnectionId(), r.validatedHeadSha(), r.checkedAt(), r.expiresAt()));
    }

    @Test
    void changedAuthoritativeSha_requiresRevalidation() {
        var r = valid();
        assertThatThrownBy(() -> receipt(r.tenantId(), r.workspaceId(), r.versionId(), r.versionRevision(), r.repositoryConnectionId(),
                "b".repeat(40), r.checkedAt(), r.expiresAt()).requireMatches(version, now))
                .isInstanceOfSatisfying(ReviewException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("SOURCE_CHANGED_REVALIDATION_REQUIRED");
                });
    }

    @Test
    void repositoryFreeVersion_doesNotRequireSourceAuthority() {
        var local = new DocumentVersion(version.id(), version.tenantId(), version.documentId(), version.workspaceId(),
                null, null, version.state(), version.reviewStage(), version.revision());
        assertThat(new SourceReviewGuard(Optional.empty()).requireCurrent(local)).isNull();
        assertThat(new SourceReviewGuard(Optional.of(v -> { throw new AssertionError("must not call S02"); })).requireCurrent(local)).isNull();
    }
}
