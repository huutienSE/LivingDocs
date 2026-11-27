package com.livingdocs.document.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.livingdocs.document.client.S01AccessContextClient;
import com.livingdocs.document.client.S01ApprovalAuthorizationClient;
import com.livingdocs.document.exception.ApprovalAuthorizationUnavailableException;
import com.livingdocs.document.exception.InsufficientApprovalScopeException;
import com.livingdocs.document.exception.ReviewAuthenticationRequiredException;
import com.livingdocs.document.exception.S01AuthorityException;
import com.livingdocs.document.model.S01AccessContext;
import com.livingdocs.document.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** Grant filtering (MANAGER + DOCUMENT_FINAL_APPROVE) and S01 failure mapping, without HTTP. */
class S01ApprovalAuthorizationClientTest {
    final S01AccessContextClient authority = mock(S01AccessContextClient.class);
    final S01ApprovalAuthorizationClient client =
            new S01ApprovalAuthorizationClient(authority, "http://127.0.0.1:1/api/v1/users/me/access-context", true);
    final Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(UUID.randomUUID().toString()).build();
    final UUID identity = UUID.randomUUID();
    final UUID tenant = UUID.randomUUID();

    S01AccessContext.Grant grant(UUID workspace, Set<String> roles, Set<String> permissions) {
        return new S01AccessContext.Grant(workspace, roles, permissions, Set.of(UUID.randomUUID()));
    }

    @Test
    void keepsOnlyManagerGrantsWithFinalApprovePermission() {
        UUID managerWs = UUID.randomUUID();
        when(authority.resolve(any(), any())).thenReturn(new S01AccessContext(identity, tenant, 1, Instant.now(), List.of(
                grant(managerWs, Set.of("MANAGER"), Set.of("DOCUMENT_FINAL_APPROVE", "DOCUMENT_READ")),
                grant(UUID.randomUUID(), Set.of("STAFF"), Set.of("DOCUMENT_STAFF_REVIEW")),
                grant(UUID.randomUUID(), Set.of("MANAGER"), Set.of("DOCUMENT_READ")),
                grant(UUID.randomUUID(), Set.of("DEVELOPER"), Set.of("DOCUMENT_FINAL_APPROVE")))));

        ActorContext actor = client.resolve(jwt);

        assertThat(actor.identityId()).isEqualTo(identity);
        assertThat(actor.tenantId()).isEqualTo(tenant);
        assertThat(actor.grants()).extracting(ActorContext.WorkspaceGrant::workspaceId).containsExactly(managerWs);
    }

    @Test
    void staffOnlyActor_getsNoGrants() {
        when(authority.resolve(any(), any())).thenReturn(new S01AccessContext(identity, tenant, 1, Instant.now(),
                List.of(grant(UUID.randomUUID(), Set.of("STAFF"), Set.of("DOCUMENT_STAFF_REVIEW")))));
        assertThat(client.resolve(jwt).grants()).isEmpty();
    }

    @Test
    void mapsS01FailuresToTypedExceptions() {
        doThrow(S01AuthorityException.unauthenticated()).when(authority).resolve(any(), any());
        assertThatThrownBy(() -> client.resolve(jwt)).isInstanceOf(ReviewAuthenticationRequiredException.class);

        doThrow(S01AuthorityException.forbidden()).when(authority).resolve(any(), any());
        assertThatThrownBy(() -> client.resolve(jwt)).isInstanceOf(InsufficientApprovalScopeException.class);

        doThrow(S01AuthorityException.unavailable()).when(authority).resolve(any(), any());
        assertThatThrownBy(() -> client.resolve(jwt)).isInstanceOf(ApprovalAuthorizationUnavailableException.class);
    }
}
