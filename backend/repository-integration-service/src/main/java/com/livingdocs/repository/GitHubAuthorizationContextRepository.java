package com.livingdocs.repository;

import com.livingdocs.entity.GitHubAuthorizationContextEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GitHubAuthorizationContextRepository extends JpaRepository<GitHubAuthorizationContextEntity, UUID> {
    Optional<GitHubAuthorizationContextEntity> findByTenantIdAndActorIdentityIdAndContextId(UUID tenantId, UUID actorIdentityId, UUID contextId);

    @Query(value = """
            select count(*) from repository.github_authorization_contexts
            where tenant_id=:tenant and workspace_id=:workspace and actor_identity_id=:actor
              and status in ('PENDING','EXCHANGING','AUTHORIZED') and expires_at > statement_timestamp()
            """, nativeQuery = true)
    long countActive(@Param("tenant") UUID tenant, @Param("workspace") UUID workspace, @Param("actor") UUID actor);

    @Query(value = """
            select greatest(1, ceil(extract(epoch from (min(expires_at)-statement_timestamp()))))::bigint
            from repository.github_authorization_contexts
            where tenant_id=:tenant and workspace_id=:workspace and actor_identity_id=:actor
              and status in ('PENDING','EXCHANGING','AUTHORIZED') and expires_at>statement_timestamp()
            """, nativeQuery = true)
    long secondsUntilNextSlot(@Param("tenant") UUID tenant, @Param("workspace") UUID workspace, @Param("actor") UUID actor);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            update repository.github_authorization_contexts set status='CANCELLED', secret_payload=null,
                row_revision=row_revision+1, updated_at=statement_timestamp()
            where tenant_id=:tenant and actor_identity_id=:actor and context_id=:context and status<>'CANCELLED'
            """, nativeQuery = true)
    int cancel(@Param("tenant") UUID tenant, @Param("actor") UUID actor, @Param("context") UUID context);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            update repository.github_authorization_contexts set status='EXCHANGING', row_revision=row_revision+1,
                updated_at=statement_timestamp()
            where context_id=:#{#b.contextId} and tenant_id=:#{#b.tenantId} and workspace_id=:#{#b.workspaceId}
              and actor_identity_id=:#{#b.actorIdentityId} and app_id=:#{#b.appId} and app_config_version=:#{#b.appConfigVersion}
              and expires_at=:#{#b.expiresAt} and expires_at>statement_timestamp() and status='PENDING' and row_revision=0
              and state_digest=:stateDigest and browser_nonce_digest=:nonceDigest and state_key_version=:stateVersion
            """, nativeQuery = true)
    int claim(@Param("b") com.livingdocs.model.GitHubAuthorizationContextBinding binding,
            @Param("stateDigest") String stateDigest, @Param("nonceDigest") String nonceDigest, @Param("stateVersion") String stateVersion);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            update repository.github_authorization_contexts set status='AUTHORIZED', secret_payload=:payload,
                github_user_id=:userId, token_expires_at=:tokenExpiry, expires_at=least(expires_at,:tokenExpiry),
                row_revision=row_revision+1, updated_at=statement_timestamp()
            where context_id=:#{#b.contextId} and tenant_id=:#{#b.tenantId} and workspace_id=:#{#b.workspaceId}
              and actor_identity_id=:#{#b.actorIdentityId} and app_id=:#{#b.appId} and app_config_version=:#{#b.appConfigVersion}
              and status='EXCHANGING' and row_revision=:revision and expires_at>statement_timestamp()
              and :tokenExpiry>statement_timestamp()
            """, nativeQuery = true)
    int complete(@Param("b") com.livingdocs.model.GitHubAuthorizationContextBinding binding, @Param("revision") long revision,
            @Param("payload") String payload, @Param("userId") String userId, @Param("tokenExpiry") java.time.Instant tokenExpiry);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            update repository.github_authorization_contexts set status='FAILED', secret_payload=null,
                row_revision=row_revision+1, updated_at=statement_timestamp()
            where context_id=:#{#b.contextId} and tenant_id=:#{#b.tenantId} and workspace_id=:#{#b.workspaceId}
              and actor_identity_id=:#{#b.actorIdentityId} and app_id=:#{#b.appId} and app_config_version=:#{#b.appConfigVersion}
              and status='EXCHANGING' and row_revision=:revision
            """, nativeQuery = true)
    int failClaim(@Param("b") com.livingdocs.model.GitHubAuthorizationContextBinding binding, @Param("revision") long revision);
}
