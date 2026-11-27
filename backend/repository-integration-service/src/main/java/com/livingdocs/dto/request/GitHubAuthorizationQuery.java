package com.livingdocs.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class GitHubAuthorizationQuery {
    public static final String UUID_PATTERN = "(?!00000000-0000-0000-0000-000000000000$)[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    @NotNull @Pattern(regexp = UUID_PATTERN)
    @Schema(format = "uuid")
    private String workspaceId;
    @Pattern(regexp = UUID_PATTERN)
    @Schema(format = "uuid", description = "Owned context; reads fail closed until Slice 2 is available")
    private String authorizationContextId;
    @Pattern(regexp = "[1-9][0-9]{0,18}")
    @Schema(description = "Positive decimal BIGINT, at most 9223372036854775807; requires authorizationContextId")
    private String installationId;
    @Pattern(regexp = "[1-9][0-9]{0,9}")
    @Schema(type = "integer", minimum = "1", maximum = "2147483647", defaultValue = "1",
            description = "Only with installationId; positive page, defaults to 1 when omitted")
    private String installationPage;
}
