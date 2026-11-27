package com.livingdocs.entity;

import com.livingdocs.model.GitHubAuthorizationContextBinding;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "github_authorization_contexts", schema = "repository")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GitHubAuthorizationContextEntity {
    @Id @Column(name = "context_id", updatable = false) private UUID contextId;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Column(name = "workspace_id", nullable = false, updatable = false) private UUID workspaceId;
    @Column(name = "actor_identity_id", nullable = false, updatable = false) private UUID actorIdentityId;
    @Column(name = "app_id", nullable = false, updatable = false, length = 19) private String appId;
    @Column(name = "app_config_version", nullable = false, updatable = false, length = 64) private String appConfigVersion;
    @Column(name = "state_digest", nullable = false, updatable = false, length = 64) private String stateDigest;
    @Column(name = "browser_nonce_digest", nullable = false, updatable = false, length = 64) private String browserNonceDigest;
    @Column(name = "state_key_version", nullable = false, updatable = false, length = 32) private String stateKeyVersion;
    @Column(name = "credential_key_version", nullable = false, updatable = false, length = 32) private String credentialKeyVersion;
    @Column(name = "secret_payload", length = 16384) private String secretPayload;
    @Column(name = "status", nullable = false, length = 16) private String status;
    @Column(name = "github_user_id", length = 19) private String githubUserId;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "token_expires_at") private Instant tokenExpiresAt;
    @Version @Column(name = "row_revision", nullable = false) private Long rowRevision;

    public GitHubAuthorizationContextEntity(GitHubAuthorizationContextBinding binding, String stateDigest,
            String nonceDigest, String secretPayload, String stateKeyVersion, String credentialKeyVersion) {
        this.contextId = binding.getContextId(); this.tenantId = binding.getTenantId();
        this.workspaceId = binding.getWorkspaceId(); this.actorIdentityId = binding.getActorIdentityId();
        this.appId = binding.getAppId(); this.appConfigVersion = binding.getAppConfigVersion();
        this.expiresAt = binding.getExpiresAt(); this.stateDigest = stateDigest; this.browserNonceDigest = nonceDigest;
        this.secretPayload = secretPayload; this.stateKeyVersion = stateKeyVersion;
        this.credentialKeyVersion = credentialKeyVersion; this.status = "PENDING";
    }

    public GitHubAuthorizationContextBinding binding() {
        return new GitHubAuthorizationContextBinding(contextId, tenantId, workspaceId, actorIdentityId,
                appId, appConfigVersion, expiresAt);
    }
}
