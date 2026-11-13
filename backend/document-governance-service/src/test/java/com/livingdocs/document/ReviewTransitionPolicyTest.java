package com.livingdocs.document;

import com.livingdocs.document.api.controller.VersionEtags;
import com.livingdocs.document.domain.model.*;
import com.livingdocs.document.domain.ReviewException;
import com.livingdocs.document.domain.policy.ReviewTransitionPolicy;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ReviewTransitionPolicyTest {
    private final ReviewTransitionPolicy policy = new ReviewTransitionPolicy();
    private final UUID id = UUID.randomUUID();

    private DocumentVersion version(DocumentState state, ReviewStage stage) {
        return new DocumentVersion(id, id, id, id, id, "a".repeat(40), state, stage, 7);
    }

    @Test
    void pass_shouldMoveToManagerApproval() {
        var before = version(DocumentState.IN_REVIEW, ReviewStage.STAFF_REVIEW);
        policy.requireStaffReview(before, ReviewAction.PASS, null, List.of(id));
        var after = before.reviewed(ReviewAction.PASS);
        assertThat(after.state()).isEqualTo(DocumentState.IN_REVIEW);
        assertThat(after.reviewStage()).isEqualTo(ReviewStage.MANAGER_APPROVAL);
        assertThat(after.revision()).isEqualTo(8);
        assertThat(before.reviewStage()).isEqualTo(ReviewStage.STAFF_REVIEW);
    }

    @Test
    void return_shouldMoveToDraft() {
        var before = version(DocumentState.IN_REVIEW, ReviewStage.STAFF_REVIEW);
        policy.requireStaffReview(before, ReviewAction.RETURN_FOR_REVISION, "Fix source references", List.of());
        var after = before.reviewed(ReviewAction.RETURN_FOR_REVISION);
        assertThat(after.state()).isEqualTo(DocumentState.DRAFT);
        assertThat(after.reviewStage()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void return_withoutReason_shouldReject(String feedback) {
        assertThatThrownBy(() -> policy.requireStaffReview(version(DocumentState.IN_REVIEW, ReviewStage.STAFF_REVIEW),
                ReviewAction.RETURN_FOR_REVISION, feedback, List.of()))
                .isInstanceOfSatisfying(ReviewException.class, e -> assertThat(e.code()).isEqualTo("RETURN_REASON_REQUIRED"));
    }

    @ParameterizedTest
    @EnumSource(value = DocumentState.class, names = "IN_REVIEW", mode = EnumSource.Mode.EXCLUDE)
    void review_fromOtherState_shouldReject(DocumentState state) {
        assertThatThrownBy(() -> policy.requireStaffReview(version(state, null), ReviewAction.PASS, null, List.of(id)))
                .isInstanceOfSatisfying(ReviewException.class, e -> assertThat(e.code()).isEqualTo("STATE_TRANSITION_FORBIDDEN"));
    }

    @Test
    void managerStage_shouldRejectStaffDecision() {
        assertThatThrownBy(() -> policy.requireStaffReview(version(DocumentState.IN_REVIEW, ReviewStage.MANAGER_APPROVAL),
                ReviewAction.PASS, null, List.of(id))).isInstanceOf(ReviewException.class);
    }

    @Test
    void pass_withoutEvidenceOrSourceBinding_shouldReject() {
        assertThatThrownBy(() -> policy.requireStaffReview(version(DocumentState.IN_REVIEW, ReviewStage.STAFF_REVIEW),
                ReviewAction.PASS, null, List.of())).isInstanceOf(ReviewException.class);
        var noSource = new DocumentVersion(id, id, id, id, id, null, DocumentState.IN_REVIEW, ReviewStage.STAFF_REVIEW, 0);
        assertThatThrownBy(() -> policy.requireStaffReview(noSource, ReviewAction.PASS, null, List.of(id)))
                .isInstanceOfSatisfying(ReviewException.class, e -> assertThat(e.code()).isEqualTo("SOURCE_EVIDENCE_INSUFFICIENT"));
    }

    @Test
    void etag_mustBeStrongAndBoundToVersion() {
        assertThat(VersionEtags.require(id, VersionEtags.format(id, 7))).isEqualTo(7);
        assertThatThrownBy(() -> VersionEtags.require(id, null)).isInstanceOfSatisfying(ReviewException.class,
                e -> assertThat(e.status()).isEqualTo(428));
        for (String invalid : List.of("*", "W/\"revision-7\"", "7", VersionEtags.format(id, 7) + "," + VersionEtags.format(id, 8))) {
            assertThatThrownBy(() -> VersionEtags.require(id, invalid)).isInstanceOf(ReviewException.class);
        }
        assertThatThrownBy(() -> VersionEtags.require(id, VersionEtags.format(UUID.randomUUID(), 7)))
                .isInstanceOfSatisfying(ReviewException.class, e -> assertThat(e.status()).isEqualTo(412));
    }
}
