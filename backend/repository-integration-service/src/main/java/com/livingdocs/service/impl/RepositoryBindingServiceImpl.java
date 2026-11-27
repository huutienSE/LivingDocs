package com.livingdocs.service.impl;

import com.livingdocs.config.ConnectionLookupProperties;
import com.livingdocs.exception.*;
import com.livingdocs.model.*;
import com.livingdocs.repository.RepositoryConnectionRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @RequiredArgsConstructor
public class RepositoryBindingServiceImpl implements com.livingdocs.service.RepositoryBindingService {
    private final EntityManager entities;
    private final RepositoryConnectionRepository repositories;
    private final ConnectionLookupProperties configuration;

    @Override @Transactional(readOnly=true, timeout=1, isolation=Isolation.REPEATABLE_READ)
    public RepositoryBindingEvidence verify(SourceScope scope, UUID connectionId) {
        entities.createNativeQuery("select set_config('app.tenant_id', :tenant, true)").setParameter("tenant",scope.tenantId().toString()).getSingleResult();
        entities.createNativeQuery("select set_config('statement_timeout', '750ms', true)").getSingleResult();
        var found = repositories.findBinding(scope.tenantId(), scope.workspaceId(), connectionId)
                .orElseThrow(() -> new SourceApiException(SourceApiError.UNKNOWN_CONNECTION));
        if (!"ACTIVE".equals(found.getRepositoryStatus()) || !configuration.githubReadyStatuses().contains(found.getGithubStatus()))
            throw new SourceApiException(SourceApiError.CONNECTION_INACTIVE);
        long revision = found.getBindingRevision();
        // A fixed transaction snapshot may miss a concurrently inserted child during parent invalidation.
        // Its recorded parent revision must still match; never serve an old positive grant proof.
        if (revision <= 0) throw new SourceApiException(SourceApiError.CONFIG_CHANGED);
        return new RepositoryBindingEvidence(scope.tenantId(),scope.workspaceId(),connectionId,"ACTIVE",revision,Instant.now());
    }
}
