package com.livingdocs.document.service.impl;

import lombok.RequiredArgsConstructor;
import com.livingdocs.document.model.*;
import com.livingdocs.document.exception.*;
import com.livingdocs.document.repository.*;
import com.livingdocs.document.security.ActorContext;
import com.livingdocs.document.service.SourceReviewGuard;
import com.livingdocs.document.service.DocumentReviewService;
import com.livingdocs.document.service.ReviewSnapshotCodec;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentReviewServiceImpl implements DocumentReviewService {
    private final DocumentVersionRepository versions;
    private final ReviewDecisionRepository decisions;
    private final DocumentAuditRepository audits;
    private final ReviewTransitionPolicy policy;
    private final SourceReviewGuard sourceGuard;
    private final ReviewSnapshotCodec snapshots;

    @Override
    @Transactional
    public StaffReviewResult review(UUID versionId, StaffDecisionCommand request, long expectedRevision,
                                 String key, ActorContext actor, String correlationId) {
        if (expectedRevision < 0 || key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ReviewRequestException(ReviewErrorCode.INVALID_REQUEST, "A valid revision and Idempotency-Key are required.");
        }
        versions.setTenant(actor.tenantId());
        // Serialize decisions for the same version, including retries and review-round allocation.
        // Scope rows stay share-locked so a concurrent move cannot invalidate authorization.
        var before = versions.find(versionId, actor.tenantId(), true).orElseThrow(DocumentVersionNotFoundException::new);
        actor.requireStaffReview(before);
        var previous = decisions.findRequest(actor.tenantId(), versionId, actor.identityId(), key);
        if (previous.isPresent()) {
            var d = previous.get();
            if (d.expectedRevision() != expectedRevision || d.decision() != request.decision()
                    || !Objects.equals(d.feedback(), request.feedback()) || !d.evidenceRefs().equals(request.evidenceRefs())) {
                throw new ReviewIdempotencyConflictException();
            }
            var snapshot = decisions.responseSnapshot(d.tenantId(), d.id())
                    .orElseThrow(ReviewReplaySnapshotUnavailableException::new);
            return snapshots.decode(snapshot);
        }
        if (before.revision() != expectedRevision) throw new ReviewRevisionConflictException();
        policy.requireStaffReview(before, request.decision(), request.feedback(), request.evidenceRefs());
        if (!versions.hasEvidence(actor.tenantId(), versionId, request.evidenceRefs())) {
            throw new ReviewEvidenceInvalidException();
        }
        var validation = request.decision() == ReviewAction.PASS ? sourceGuard.requireCurrent(before) : null;
        var after = before.reviewed(request.decision());
        var now = Instant.now();
        var decision = new ReviewDecision(UUID.randomUUID(), actor.tenantId(), versionId, actor.identityId(),
                request.decision(), request.feedback(), decisions.nextRound(actor.tenantId(), versionId), key,
                expectedRevision, after.revision(), after.state(), after.reviewStage(), request.evidenceRefs(), now);
        // Recheck expiry immediately before the conditional write; the version/scope rows remain locked.
        if (validation != null) validation.requireMatches(before, Instant.now());
        versions.update(before, after);
        var result = StaffReviewResult.recorded(decision, before);
        var snapshot = snapshots.encode(result);
        decisions.insert(decision, snapshot);
        audits.insert(new DocumentAudit(UUID.randomUUID(), before, after, actor.identityId(), decision, correlationId, now, validation));
        // Repository flushes surface failures before returning; all three writes share one transaction.
        return result;
    }
}
