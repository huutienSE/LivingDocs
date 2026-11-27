package com.livingdocs.document.service.impl;

import com.livingdocs.document.config.ReviewQueueProperties;
import com.livingdocs.document.dto.request.ReviewQueueQuery;
import com.livingdocs.document.dto.response.ReviewQueueView;
import com.livingdocs.document.entity.ReviewQueueEntry;
import com.livingdocs.document.exception.InsufficientReviewScopeException;
import com.livingdocs.document.exception.InvalidReviewQueueQueryException;
import com.livingdocs.document.mapper.ReviewQueueMapper;
import com.livingdocs.document.model.S01AccessContext;
import com.livingdocs.document.repository.*;
import com.livingdocs.document.service.ReviewQueueService;
import com.livingdocs.document.security.StaffReadPolicy;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @RequiredArgsConstructor
public class ReviewQueueServiceImpl implements ReviewQueueService {
    private final ReviewQueueJpaRepository queue;
    private final DocumentVersionRepository tenants;
    private final ReviewQueueMapper mapper;
    private final ReviewQueueProperties properties;

    // API-038 was resolved before entering this transaction; count and content share a snapshot.
    @Override @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReviewQueueView list(S01AccessContext actor, ReviewQueueQuery request) {
        var grants = StaffReadPolicy.grants(actor).stream()
                .filter(g -> request.getWorkspaceId() == null || request.getWorkspaceId().equals(g.workspaceId()))
                .filter(g -> request.getRepositoryConnectionId() == null || g.repositoryIds().contains(request.getRepositoryConnectionId()))
                .toList();
        if (grants.isEmpty()) throw new InsufficientReviewScopeException();
        int size = request.getSize() == null ? properties.getDefaultPageSize() : request.getSize();
        if (size < 1 || size > properties.getMaxPageSize() || request.getPage() < 0
                || (long) request.getPage() * size > Integer.MAX_VALUE
                || request.getSort() == null || request.getDirection() == null
                || request.getSubmittedFrom() != null && request.getSubmittedTo() != null
                    && request.getSubmittedFrom().isAfter(request.getSubmittedTo()))
            throw new InvalidReviewQueueQueryException();
        tenants.setTenant(actor.tenantId());
        queue.setStatementTimeout(Integer.toString(properties.getQueryTimeoutMs()));
        Specification<ReviewQueueEntry> specification = (root, query, cb) -> {
            var scopes = new ArrayList<Predicate>();
            for (var grant : grants) {
                var repository = cb.isNull(root.get("repositoryConnectionId"));
                if (!grant.repositoryIds().isEmpty()) repository = cb.or(repository, root.get("repositoryConnectionId").in(grant.repositoryIds()));
                scopes.add(cb.and(cb.equal(root.get("workspaceId"), grant.workspaceId()), repository));
            }
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("tenantId"), actor.tenantId()));
            predicates.add(cb.or(scopes.toArray(Predicate[]::new)));
            if (request.getRepositoryConnectionId() != null) predicates.add(cb.equal(root.get("repositoryConnectionId"), request.getRepositoryConnectionId()));
            if (request.getTemplateId() != null) predicates.add(cb.equal(root.get("templateId"), request.getTemplateId()));
            if (request.getDriftSeverity() != null) predicates.add(cb.equal(root.get("driftSeverity"), request.getDriftSeverity()));
            if (request.getSubmittedFrom() != null) predicates.add(cb.greaterThanOrEqualTo(root.get("submittedAt"), request.getSubmittedFrom()));
            if (request.getSubmittedTo() != null) predicates.add(cb.lessThanOrEqualTo(root.get("submittedAt"), request.getSubmittedTo()));
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                String property = switch (request.getSort()) {
                    case REPOSITORY -> "repositoryConnectionId";
                    case TEMPLATE -> "templateId";
                    case DRIFT_SEVERITY -> "severityRank";
                    case SUBMISSION_DATE -> "submittedAt";
                };
                var field = root.get(property);
                query.orderBy(cb.asc(cb.<Integer>selectCase().when(cb.isNull(field), 1).otherwise(0)),
                        request.getDirection() == ReviewQueueQuery.Direction.ASC ? cb.asc(field) : cb.desc(field),
                        cb.asc(root.get("versionId")));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        var page = queue.findAll(specification, PageRequest.of(request.getPage(), size));
        return new ReviewQueueView(page.getContent().stream().map(mapper::toResponse).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
