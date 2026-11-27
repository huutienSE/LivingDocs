package com.livingdocs.controller;

import com.livingdocs.model.GitHubAuthorizationError;
import com.livingdocs.exception.GitHubAuthorizationException;
import com.livingdocs.security.GitHubAuthorizationCookies;
import com.livingdocs.security.SourceCorrelationFilter;
import com.livingdocs.service.GitHubAuthorizationContextService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class GitHubAuthorizationCallbackController {
    private final GitHubAuthorizationContextService contexts;
    private final com.livingdocs.service.impl.GitHubAuthorizationRequestExecutor requests;

    @GetMapping("/api/v1/integrations/github/authorization/callback")
    @Operation(operationId = "githubAuthorizationCallback", summary = "GitHub App user authorization protocol callback")
    public ResponseEntity<Void> callback(@RequestParam String state, @RequestParam(required = false) String code,
            @RequestParam(required = false) String error, HttpServletRequest request) {
        var cookies = new HashMap<String, String>();
        if (request.getCookies() != null) for (var cookie : request.getCookies()) {
            if (cookie.getName().startsWith("__Secure-ld-gh-") && cookies.putIfAbsent(cookie.getName(), cookie.getValue()) != null)
                throw new GitHubAuthorizationException(GitHubAuthorizationError.OAUTH_STATE_INVALID);
        }
        String correlation = (String)request.getAttribute(SourceCorrelationFilter.ATTRIBUTE);
        var completed = requests.execute(com.livingdocs.security.GitHubAuthorizationDeadlineFilter.deadline(request),
                () -> contexts.complete(state, cookies, code, error, correlation));
        return ResponseEntity.status(303).cacheControl(CacheControl.noStore()).header(HttpHeaders.LOCATION, completed.getCompletionUrl())
                .header(HttpHeaders.SET_COOKIE, GitHubAuthorizationCookies.clear(completed.getContextId()).toString()).build();
    }
}
