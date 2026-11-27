package com.livingdocs.document.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.livingdocs.document.exception.DocumentVersionNotFoundException;
import com.livingdocs.document.exception.InsufficientApprovalScopeException;
import com.livingdocs.document.model.ApprovalContext;
import com.livingdocs.document.model.ApprovalQueueItem;
import com.livingdocs.document.model.DocumentState;
import com.livingdocs.document.model.ReviewStage;
import com.livingdocs.document.repository.ApprovalQueryRepository;
import com.livingdocs.document.security.ActorContext;
import com.livingdocs.document.service.impl.DocumentApprovalQueryServiceImpl;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Business rules of the service with the repository mocked (unit level; DB behaviour is in the integration test). */
class DocumentApprovalQueryServiceImplTest {
    final ApprovalQueryRepository repository = mock(ApprovalQueryRepository.class);
    final DocumentApprovalQueryServiceImpl service = new DocumentApprovalQueryServiceImpl(repository);
    final UUID tenant = UUID.randomUUID();
    final UUID workspace = UUID.randomUUID();
    final UUID repo = UUID.randomUUID();
    final ActorContext manager = new ActorContext(UUID.randomUUID(), tenant,
            Set.of(new ActorContext.WorkspaceGrant(workspace, Set.of("MANAGER"), Set.of(repo))));
    final ActorContext noGrants = new ActorContext(UUID.randomUUID(), tenant, Set.of());

    ApprovalQueryRepository.Header header(UUID version, UUID ws, UUID repository, DocumentState state, ReviewStage stage) {
        return new ApprovalQueryRepository.Header(new ApprovalContext.Version(UUID.randomUUID(), version, state, stage, 3, null),
                "Doc", 1, ws, repository);
    }

    @Test
    void queue_withoutManagerGrant_isForbiddenAndDoesNotQueryDatabase() {
        assertThatThrownBy(() -> service.queue(noGrants, 0, 20)).isInstanceOf(InsufficientApprovalScopeException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void queue_setsTenantAndReturnsPageWithTotals() {
        var item = new ApprovalQueueItem(UUID.randomUUID(), UUID.randomUUID(), "Doc", 1, workspace, repo, "sha", 2,
                DocumentState.IN_REVIEW, ReviewStage.MANAGER_APPROVAL, Instant.now());
        when(repository.countQueue(any())).thenReturn(3L);
        when(repository.findQueue(any(), anyInt(), anyInt())).thenReturn(List.of(item));

        var page = service.queue(manager, 1, 2);

        verify(repository).setTenant(tenant);
        assertThat(page.items()).containsExactly(item);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.page()).isEqualTo(1);
    }

    @Test
    void queue_pageBeyondTotal_returnsEmptyWithoutLoadingRows() {
        when(repository.countQueue(any())).thenReturn(2L);
        var page = service.queue(manager, 5, 20);
        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(2);
        verify(repository, never()).findQueue(any(), anyInt(), anyInt());
    }

    @Test
    void context_unknownVersion_isNotFound() {
        when(repository.findHeader(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.context(UUID.randomUUID(), manager))
                .isInstanceOf(DocumentVersionNotFoundException.class);
    }

    @Test
    void context_outsideScope_isForbiddenAndLoadsNoDetails() {
        UUID version = UUID.randomUUID();
        when(repository.findHeader(tenant, version)).thenReturn(Optional.of(
                header(version, UUID.randomUUID(), null, DocumentState.IN_REVIEW, ReviewStage.MANAGER_APPROVAL)));
        assertThatThrownBy(() -> service.context(version, manager)).isInstanceOf(InsufficientApprovalScopeException.class);
        verify(repository, never()).findStaffDecisions(any(), any());
        verify(repository, never()).findEvidence(any(), any());
    }

    @Test
    void context_atManagerApproval_isAwaitingDecision() {
        UUID version = UUID.randomUUID();
        when(repository.findHeader(tenant, version)).thenReturn(Optional.of(
                header(version, workspace, repo, DocumentState.IN_REVIEW, ReviewStage.MANAGER_APPROVAL)));
        when(repository.findCurrentContent(tenant, version)).thenReturn(Optional.empty());
        when(repository.findStaffDecisions(tenant, version)).thenReturn(List.of());
        when(repository.findApprovalDecisions(tenant, version)).thenReturn(List.of());
        when(repository.findEvidence(tenant, version)).thenReturn(List.of());

        var context = service.context(version, manager);

        assertThat(context.awaitingManagerDecision()).isTrue();
        assertThat(context.currentContent()).isNull();
        verify(repository).setTenant(tenant);
    }

    @Test
    void context_atStaffReviewOrApproved_isReadableButNotAwaiting() {
        for (var state : List.of(new Object[]{DocumentState.IN_REVIEW, ReviewStage.STAFF_REVIEW},
                new Object[]{DocumentState.APPROVED, null}, new Object[]{DocumentState.DRAFT, null})) {
            UUID version = UUID.randomUUID();
            when(repository.findHeader(tenant, version)).thenReturn(Optional.of(
                    header(version, workspace, repo, (DocumentState) state[0], (ReviewStage) state[1])));
            when(repository.findCurrentContent(tenant, version)).thenReturn(Optional.empty());
            assertThat(service.context(version, manager).awaitingManagerDecision()).as(state[0].toString()).isFalse();
        }
    }
}
