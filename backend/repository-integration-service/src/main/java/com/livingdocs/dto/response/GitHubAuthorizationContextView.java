package com.livingdocs.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@Schema(name = "GitHubAuthorizationContextStartView", requiredProperties = {"contextId", "expiresAt", "authorizationUrl", "installationUrl"})
public class GitHubAuthorizationContextView {
    private final UUID contextId;
    private final Instant expiresAt;
    private final String authorizationUrl;
    private final String installationUrl;
    public GitHubAuthorizationContextView(UUID contextId, Instant expiresAt, String authorizationUrl, String installationUrl) {
        this.contextId = contextId; this.expiresAt = expiresAt; this.authorizationUrl = authorizationUrl; this.installationUrl = installationUrl;
    }
}
