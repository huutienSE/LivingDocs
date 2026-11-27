package com.livingdocs.controller;

import com.livingdocs.dto.request.GitHubAuthorizationQuery;
import com.livingdocs.dto.response.*;
import com.livingdocs.mapper.GitHubAuthorizationMapper;
import com.livingdocs.model.*;
import com.livingdocs.security.SourceCorrelationFilter;
import com.livingdocs.service.GitHubAuthorizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springdoc.core.annotations.ParameterObject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/integrations/github/authorization")
@SecurityRequirement(name = "bearerAuth")
public class GitHubAuthorizationController {
    private final GitHubAuthorizationService service;
    private final GitHubAuthorizationMapper mapper;
    private final com.livingdocs.service.impl.GitHubAuthorizationRequestExecutor requests;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "api046_get", summary = "GitHub authorization requirements and scoped context")
    public ResponseEntity<SuccessResponse<GitHubAuthorizationView>> read(
            @Valid @ModelAttribute @ParameterObject GitHubAuthorizationQuery query, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        var actor = new GitHubAuthorizationUser(UUID.fromString(jwt.getSubject()), jwt.getTokenValue());
        String correlation = (String)request.getAttribute(SourceCorrelationFilter.ATTRIBUTE);
        var result = requests.execute(com.livingdocs.security.GitHubAuthorizationDeadlineFilter.deadline(request),
                () -> service.read(actor, query, correlation));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(SuccessCode.GITHUB_AUTHORIZATION_CONTEXT_READ,mapper.toView(result)));
    }
}
