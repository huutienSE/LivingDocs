package com.livingdocs.document.repository;
import com.livingdocs.document.model.*;
import com.livingdocs.document.exception.ReviewRevisionConflictException;
import com.livingdocs.document.exception.DocumentVersionNotFoundException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
@Repository 
@RequiredArgsConstructor 
@Transactional(propagation = Propagation.MANDATORY)
public class DocumentSubmitRepository {
    private final DocumentEvidenceJpaRepository evidence;
    private final DocumentVersionJpaRepository versions;
    public List<UUID> evidence(UUID tenant, UUID version) {
        return evidence.findUsableIds(tenant, version);
    }
    public SubmitResult.Binding binding(UUID tenant, UUID version) {
        var v = versions.findById(version).filter(row -> row.getTenantId().equals(tenant))
                .orElseThrow(DocumentVersionNotFoundException::new);
        return new SubmitResult.Binding(v.getSourceTargetKind(), v.getSourceTargetRef(),
                v.getSourceComparisonSha(), v.getSourceConfigRevision(), v.getSourceCapturedAt());
    }
    public void submit(DocumentVersion before) {
        var v = versions.findById(before.id()).orElseThrow();
        if (!v.getTenantId().equals(before.tenantId()) || v.getState() != DocumentState.DRAFT || v.getReviewStage() != null || v.getRevision() != before.revision()) throw new ReviewRevisionConflictException();
        v.review(DocumentState.IN_REVIEW, ReviewStage.STAFF_REVIEW); versions.flush();
        if (v.getRevision() != before.revision() + 1) throw new ReviewRevisionConflictException();
    }
}
