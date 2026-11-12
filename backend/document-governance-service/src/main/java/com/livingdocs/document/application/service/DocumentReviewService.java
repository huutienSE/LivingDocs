package com.livingdocs.document.application.service;

import com.livingdocs.document.domain.model.*;
import com.livingdocs.document.domain.ReviewException;
import com.livingdocs.document.domain.policy.ReviewTransitionPolicy;
import com.livingdocs.document.api.request.ReviewRequest;
import com.livingdocs.document.api.response.ReviewResponse;
import com.livingdocs.document.infrastructure.persistence.*;
import com.livingdocs.document.security.ActorContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentReviewService {
    private final DocumentVersionRepository versions;
    private final ReviewDecisionRepository decisions;
    private final DocumentAuditRepository audits;
    private final ReviewTransitionPolicy policy;
    private final SourceReviewGuard sourceGuard;

    public DocumentReviewService(DocumentVersionRepository versions, ReviewDecisionRepository decisions,
                                 DocumentAuditRepository audits, ReviewTransitionPolicy policy, SourceReviewGuard sourceGuard) {
        this.versions = versions;
        this.decisions = decisions;
        this.audits = audits;
        this.policy = policy;
        this.sourceGuard = sourceGuard;
    }

    @Transactional
    public ReviewResponse review(UUID versionId, ReviewRequest request, long expectedRevision,
                                 String key, ActorContext actor, String correlationId) {
        if (expectedRevision < 0 || key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ReviewException(400, "INVALID_REQUEST", "A valid revision and Idempotency-Key are required.");
        }
        versions.setTenant(actor.tenantId());
        // Serialize decisions for the same version, including retries and review-round allocation.
        // Scope rows stay share-locked so a concurrent move cannot invalidate authorization.
        var before = versions.find(versionId, actor.tenantId(), true).orElseThrow(ReviewException::notFound);
        actor.requireStaffReview(before);
        var previous = decisions.findRequest(actor.tenantId(), versionId, actor.identityId(), key);
        if (previous.isPresent()) {
            var d = previous.get();
            if (d.expectedRevision() != expectedRevision || d.decision() != request.decision()
                    || !Objects.equals(d.feedback(), request.feedback()) || !d.evidenceRefs().equals(request.evidenceRefs())) {
                throw new ReviewException(409, "IDEMPOTENCY_CONFLICT", "Idempotency-Key was already used for another request.");
            }
            return decisions.response(d);
        }
        if (before.revision() != expectedRevision) throw ReviewException.revisionConflict();
        policy.requireStaffReview(before, request.decision(), request.feedback(), request.evidenceRefs());
        if (!versions.hasEvidence(actor.tenantId(), versionId, request.evidenceRefs())) {
            throw new ReviewException(422, "REVIEW_EVIDENCE_INVALID", "Evidence must belong to this document version.");
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
        decisions.insert(decision, ReviewResponse.from(decision, before));
        audits.insert(new DocumentAudit(UUID.randomUUID(), before, after, actor.identityId(), decision, correlationId, now, validation));
        // JDBC statements execute immediately; any failure rolls back all three writes.
        return ReviewResponse.from(decision, before);
    }
}
