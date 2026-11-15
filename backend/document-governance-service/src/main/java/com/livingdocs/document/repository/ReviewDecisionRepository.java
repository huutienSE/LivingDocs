package com.livingdocs.document.repository;

import com.livingdocs.document.exception.*;
import com.livingdocs.document.exception.ReviewException;
import com.livingdocs.document.dto.response.ReviewResponse;
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

    public ReviewResponse response(ReviewDecision d) {
        String snapshot = decisions.responseSnapshot(d.tenantId(), d.id());
        if (snapshot == null) throw new ReviewReplaySnapshotUnavailableException();
        return json.readValue(snapshot, ReviewResponse.class);
    }

    public void insert(ReviewDecision d, ReviewResponse response) {
        // Persist assigned UUIDs as new rows; never merge append-only history.
        decisions.saveAndFlush(mapper.toEntity(d, json.writeValueAsString(d.evidenceRefs()), json.writeValueAsString(response)));
    }
}
