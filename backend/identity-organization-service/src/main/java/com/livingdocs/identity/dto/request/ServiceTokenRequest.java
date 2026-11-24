package com.livingdocs.identity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class ServiceTokenRequest {
    @NotBlank private String audience; // đích
    @NotBlank private String operation; // tên thao tác nghiệp vụ
    @NotBlank @Schema(allowableValues = "POST") private String method;
    @NotBlank private String path; // endpoint của service đích
    @NotNull private UUID tenantId;
    private UUID workspaceId;
    @Schema(description = "Required for DOCUMENT_* and source operations; omit for REPOSITORY_CONNECT")
    private UUID repositoryConnectionId;
    private String action;
    @Schema(description = "SHA-256 of the raw User JWT for API-039; omit for service-only source operations",
            pattern = "^[0-9a-f]{64}$")
    private String actorTokenSha256;
}
