package com.livingdocs.document;

import com.livingdocs.document.config.ReviewQueueProperties;
import com.livingdocs.document.dto.request.ReviewQueueQuery;
import com.livingdocs.document.exception.*;
import com.livingdocs.document.mapper.ReviewQueueMapper;
import com.livingdocs.document.model.S01AccessContext;
import com.livingdocs.document.repository.*;
import com.livingdocs.document.service.impl.ReviewQueueServiceImpl;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewQueueServiceTest {
    @Test void mutationPermissionDoesNotGrantReadAndNoDatabaseIsTouchedOnDenial() {
        var repository = mock(ReviewQueueJpaRepository.class);
        var tenants = mock(DocumentVersionRepository.class);
        var service = new ReviewQueueServiceImpl(repository, tenants, mock(ReviewQueueMapper.class), new ReviewQueueProperties());
        var actor = new S01AccessContext(UUID.randomUUID(), UUID.randomUUID(), 1, Instant.now(),
                List.of(new S01AccessContext.Grant(UUID.randomUUID(), Set.of("STAFF"), Set.of("DOCUMENT_STAFF_REVIEW"), Set.of())));
        assertThatThrownBy(() -> service.list(actor, new ReviewQueueQuery())).isInstanceOf(InsufficientReviewScopeException.class);
        verifyNoInteractions(repository, tenants);
    }

    @Test void environmentQueryBudgetIsEnforcedBeforePersistence() {
        var repository = mock(ReviewQueueJpaRepository.class);
        var tenants = mock(DocumentVersionRepository.class);
        var properties = new ReviewQueueProperties(); properties.setMaxPageSize(7); properties.setDefaultPageSize(7);
        var service = new ReviewQueueServiceImpl(repository, tenants, mock(ReviewQueueMapper.class), properties);
        var actor = new S01AccessContext(UUID.randomUUID(), UUID.randomUUID(), 1, Instant.now(),
                List.of(new S01AccessContext.Grant(UUID.randomUUID(), Set.of("STAFF"), Set.of("DOCUMENT_READ", "WORKSPACE_VIEW"), Set.of())));
        var query = new ReviewQueueQuery(); query.setSize(8);
        assertThatThrownBy(() -> service.list(actor, query)).isInstanceOf(InvalidReviewQueueQueryException.class);
        verifyNoInteractions(repository, tenants);
    }
}
