package com.livingdocs.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class GitHubAuthorizationPageQuery {
    @Pattern(regexp = "[1-9][0-9]{0,9}")
    @Schema(type = "integer", minimum = "1", maximum = "2147483647", defaultValue = "1")
    private String page = "1";
    public int toPage() { return Integer.parseInt(page); }
}
