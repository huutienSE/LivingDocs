package com.livingdocs.document.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.livingdocs.document.exception.InsufficientApprovalScopeException;
import com.livingdocs.document.model.ApprovalScope;
import com.livingdocs.document.security.ActorContext;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** White-box tests for the Manager read scope (equivalence classes of tenant/workspace/repository). */
class ApprovalScopeTest {
    final UUID identity = UUID.randomUUID();
    final UUID tenant = UUID.randomUUID();
    final UUID workspaceA = UUID.randomUUID();
    final UUID workspaceB = UUID.randomUUID();
    final UUID repoA = UUID.randomUUID();
    final UUID repoB = UUID.randomUUID();

    ActorContext actor(ActorContext.WorkspaceGrant... grants) {
        return new ActorContext(identity, tenant, Set.of(grants));
    }

    ActorContext.WorkspaceGrant grant(UUID workspace, UUID... repos) {
        return new ActorContext.WorkspaceGrant(workspace, Set.of("MANAGER"), Set.of(repos));
    }

    @Test
    void of_withoutAnyManagerGrant_isForbidden() {
        assertThatThrownBy(() -> ApprovalScope.of(actor())).isInstanceOf(InsufficientApprovalScopeException.class);
    }

    @Test
    void of_withoutTenant_isForbidden() {
        var noTenant = new ActorContext(identity, null, Set.of(grant(workspaceA, repoA)));
        assertThatThrownBy(() -> ApprovalScope.of(noTenant)).isInstanceOf(InsufficientApprovalScopeException.class);
    }

    @Test
    void covers_grantedWorkspaceAndRepository() {
        var scope = ApprovalScope.of(actor(grant(workspaceA, repoA)));
        assertThat(scope.covers(tenant, workspaceA, repoA)).isTrue();
    }

    @Test
    void covers_repositoryFreeDocumentInGrantedWorkspace() {
        var scope = ApprovalScope.of(actor(grant(workspaceA)));
        assertThat(scope.covers(tenant, workspaceA, null)).isTrue();
    }

    @Test
    void doesNotCover_otherTenant_otherWorkspace_orUngrantedRepository() {
        var scope = ApprovalScope.of(actor(grant(workspaceA, repoA)));
        assertThat(scope.covers(UUID.randomUUID(), workspaceA, repoA)).isFalse();
        assertThat(scope.covers(tenant, workspaceB, null)).isFalse();
        assertThat(scope.covers(tenant, workspaceA, repoB)).isFalse();
    }

    @Test
    void doesNotCombineRepositoryGrantsAcrossWorkspaces() {
        // repoB is granted only in workspaceB; it must not unlock repoB documents in workspaceA.
        var scope = ApprovalScope.of(actor(grant(workspaceA, repoA), grant(workspaceB, repoB)));
        assertThat(scope.covers(tenant, workspaceA, repoB)).isFalse();
        assertThat(scope.covers(tenant, workspaceB, repoB)).isTrue();
    }

    @Test
    void require_outsideScope_throwsForbidden() {
        var scope = ApprovalScope.of(actor(grant(workspaceA, repoA)));
        assertThatThrownBy(() -> scope.require(tenant, workspaceB, repoA))
                .isInstanceOf(InsufficientApprovalScopeException.class);
    }
}
