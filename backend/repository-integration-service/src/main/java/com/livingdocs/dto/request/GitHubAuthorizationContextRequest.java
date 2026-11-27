package com.livingdocs.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class GitHubAuthorizationContextRequest {
    @NotNull @Pattern(regexp = GitHubAuthorizationQuery.UUID_PATTERN) @Schema(format = "uuid")
    private String workspaceId;
}
