package com.livingdocs.exception;

import com.livingdocs.controller.GitHubAuthorizationController;
import com.livingdocs.model.GitHubAuthorizationError;
import com.livingdocs.security.SourceCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import tools.jackson.databind.json.JsonMapper;

@RestControllerAdvice(assignableTypes = GitHubAuthorizationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class GitHubAuthorizationProblems {
    private final JsonMapper json;

    public ProblemDetail body(GitHubAuthorizationError error, HttpServletRequest request) {
        var p = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(error.getStatus()), error.getDetail());
        p.setType(URI.create("urn:livingdocs:problem:" + error.name().toLowerCase(java.util.Locale.ROOT).replace('_','-')));
        p.setTitle(HttpStatus.valueOf(error.getStatus()).getReasonPhrase());
        p.setInstance(URI.create("urn:uuid:" + UUID.randomUUID()));
        p.setProperty("code", error.name());
        p.setProperty("correlationId", request.getAttribute(SourceCorrelationFilter.ATTRIBUTE));
        return p;
    }

    public void write(GitHubAuthorizationError error, HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(error.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        if (error == GitHubAuthorizationError.UNAUTHENTICATED) response.setHeader("WWW-Authenticate", "Bearer");
        if (error == GitHubAuthorizationError.METHOD_NOT_ALLOWED) response.setHeader("Allow", "GET, HEAD");
        json.writeValue(response.getOutputStream(), body(error, request));
    }

    @ExceptionHandler(GitHubAuthorizationException.class)
    ResponseEntity<ProblemDetail> rejection(GitHubAuthorizationException e, HttpServletRequest request) {
        var response = ResponseEntity.status(e.getError().getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore());
        if (e.getError() == GitHubAuthorizationError.UNAUTHENTICATED) response.header("WWW-Authenticate", "Bearer");
        return response.body(body(e.getError(), request));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalid(HttpServletRequest request) {
        return rejection(new GitHubAuthorizationException(GitHubAuthorizationError.INVALID_REQUEST), request);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(HttpServletRequest request) {
        return rejection(new GitHubAuthorizationException(GitHubAuthorizationError.INTERNAL_ERROR), request);
    }
}
