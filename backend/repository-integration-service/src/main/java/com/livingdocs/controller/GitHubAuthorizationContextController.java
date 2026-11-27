package com.livingdocs.controller;

import com.livingdocs.dto.request.GitHubAuthorizationContextRequest;
import com.livingdocs.dto.request.GitHubAuthorizationPageQuery;
import com.livingdocs.dto.response.*;
import com.livingdocs.mapper.GitHubAuthorizationMapper;
import com.livingdocs.model.GitHubAuthorizationUser;
import com.livingdocs.model.SuccessCode;
import com.livingdocs.security.GitHubAuthorizationCookies;
import com.livingdocs.security.SourceCorrelationFilter;
import com.livingdocs.service.GitHubAuthorizationContextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/integrations/github/authorization-contexts")
@SecurityRequirement(name = "bearerAuth")
public class GitHubAuthorizationContextController {
    private final GitHubAuthorizationContextService contexts;
    private final GitHubAuthorizationMapper mapper;
    private final com.livingdocs.service.impl.GitHubAuthorizationRequestExecutor requests;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "githubAuthorizationContextCreate", summary = "Start owner-bound GitHub user authorization")
    public ResponseEntity<SuccessResponse<GitHubAuthorizationContextView>> start(@Valid @RequestBody GitHubAuthorizationContextRequest body,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        var actor = actor(jwt); UUID workspace = UUID.fromString(body.getWorkspaceId());
        String origin = request.getHeader("Origin"), correlation = correlation(request);
        var started = requests.execute(com.livingdocs.security.GitHubAuthorizationDeadlineFilter.deadline(request),
                () -> contexts.start(actor, workspace, origin, correlation));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE,
                GitHubAuthorizationCookies.nonce(started.getContextId(), started.getCookieNonce(), started.getExpiresAt()).toString())
                .body(SuccessResponse.of(SuccessCode.GITHUB_AUTHORIZATION_CONTEXT_CREATED, mapper.toContext(started)));
    }

    @GetMapping(value = "/{contextId}/installations", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "githubAuthorizationInstallationsList", summary = "List one page of user-accessible App installations")
    public ResponseEntity<SuccessResponse<GitHubInstallationListView>> installations(@PathVariable UUID contextId,
            @Valid @ModelAttribute @ParameterObject GitHubAuthorizationPageQuery query, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        var actor = actor(jwt); int number = query.toPage(); String correlation = correlation(request);
        var page = requests.execute(com.livingdocs.security.GitHubAuthorizationDeadlineFilter.deadline(request),
                () -> contexts.installations(actor, contextId, number, correlation));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SuccessResponse.of(SuccessCode.GITHUB_INSTALLATIONS_LISTED, mapper.toInstallations(page)));
    }

    @GetMapping(value = "/{contextId}/installations/{installationId}/repositories", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "githubAuthorizationRepositoriesList", summary = "List one repository page using user-scoped provider proof")
    public ResponseEntity<SuccessResponse<GitHubRepositoryListView>> repositories(@PathVariable UUID contextId, @PathVariable String installationId,
            @Valid @ModelAttribute @ParameterObject GitHubAuthorizationPageQuery query, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        var actor = actor(jwt); int number = query.toPage(); String correlation = correlation(request);
        var page = requests.execute(com.livingdocs.security.GitHubAuthorizationDeadlineFilter.deadline(request),
                () -> contexts.repositories(actor, contextId, installationId, number, correlation));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SuccessResponse.of(SuccessCode.GITHUB_REPOSITORIES_LISTED, mapper.toRepositories(page)));
    }

    @DeleteMapping("/{contextId}")
    @Operation(operationId = "githubAuthorizationContextCancel", summary = "Cancel context and purge its local credential")
    public ResponseEntity<Void> cancel(@PathVariable UUID contextId, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        var actor = actor(jwt); String correlation = correlation(request);
        requests.execute(com.livingdocs.security.GitHubAuthorizationDeadlineFilter.deadline(request),
                () -> { contexts.cancel(actor, contextId, correlation); return null; });
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, GitHubAuthorizationCookies.clear(contextId).toString()).build();
    }
    private static GitHubAuthorizationUser actor(Jwt jwt) { return new GitHubAuthorizationUser(UUID.fromString(jwt.getSubject()), jwt.getTokenValue()); }
    private static String correlation(HttpServletRequest request) { return (String)request.getAttribute(SourceCorrelationFilter.ATTRIBUTE); }
}
