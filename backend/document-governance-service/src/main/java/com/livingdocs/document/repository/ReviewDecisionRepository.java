package com.livingdocs.document.repository;

import lombok.RequiredArgsConstructor;
import com.livingdocs.document.model.ReviewDecision;
import com.livingdocs.document.mapper.ReviewPersistenceMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class ReviewDecisionRepository {
    private final ReviewDecisionJpaRepository decisions;
    private final JsonMapper json;
    private final ReviewPersistenceMapper mapper;

    public Optional<ReviewDecision> findRequest(UUID tenant, UUID version, UUID actor, String key) {
        return decisions.findByTenantIdAndVersionIdAndReviewerIdAndIdempotencyKey(tenant, version, actor, key)
                .map(d -> mapper.toModel(d,
                        json.readValue(d.getEvidenceRefsJson(), new TypeReference<List<UUID>>() {})));
    }

    public int nextRound(UUID tenant, UUID version) {
        // The version row is locked before allocating a new review round.
        return decisions.nextRound(tenant, version);
    }

    public Optional<String> responseSnapshot(UUID tenantId, UUID decisionId) {
        return Optional.ofNullable(decisions.responseSnapshot(tenantId, decisionId));
    }

    public void insert(ReviewDecision d, String responseSnapshot) {
        // Persist assigned UUIDs as new rows; never merge append-only history.
        decisions.saveAndFlush(mapper.toEntity(d, json.writeValueAsString(d.evidenceRefs()), responseSnapshot));
    }
}
