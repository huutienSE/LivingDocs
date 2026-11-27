package com.livingdocs.security;

import com.livingdocs.client.GitHubAuthorizationClient;
import com.livingdocs.client.GitHubAuthorizationIo;
import com.livingdocs.config.GitHubAuthorizationProperties;
import com.livingdocs.dto.request.GitHubAuthorizationQuery;
import com.livingdocs.exception.GitHubAuthorizationException;
import com.livingdocs.exception.GitHubAuthorizationProblems;
import com.livingdocs.model.GitHubAuthorizationError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public final class GitHubAuthorizationContextRequestFilter extends OncePerRequestFilter {
    private static final String BASE = "/api/v1/integrations/github/authorization-contexts";
    private static final String CALLBACK = "/api/v1/integrations/github/authorization/callback";
    private final GitHubAuthorizationProblems problems;
    private final GitHubAuthorizationProperties config;
    private final GitHubAuthorizationBodyGuard body;
    private final boolean callback;

    public GitHubAuthorizationContextRequestFilter(GitHubAuthorizationProblems problems, GitHubAuthorizationProperties config,
                                                   GitHubAuthorizationIo io, boolean callback) {
        this.problems = problems; this.config = config; this.body = new GitHubAuthorizationBodyGuard(problems, io); this.callback = callback;
    }
    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return callback ? !CALLBACK.equals(request.getRequestURI()) : !request.getRequestURI().startsWith(BASE);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (!config.isAllowLocalHttp() && !request.isSecure()) fail(GitHubAuthorizationError.USER_TOKEN_TRUST_UNAVAILABLE);
            if (callback) {
                request.setAttribute(GitHubAuthorizationProblems.ALLOW_ATTRIBUTE, "GET");
                if (!"GET".equals(request.getMethod())) fail(GitHubAuthorizationError.METHOD_NOT_ALLOWED);
                noDeclaredBody(request); query(request, Set.of("state", "code", "error"), 8192);
                String state = request.getParameter("state"), code = request.getParameter("code"), error = request.getParameter("error");
                if (state == null || state.length() > 2048 || (code == null) == (error == null)
                        || (code != null && code.length() > 2048)) fail(GitHubAuthorizationError.OAUTH_STATE_INVALID);
                if (body.ready(request, response)) chain.doFilter(request, response);
                return;
            }
            boolean create = request.getRequestURI().equals(BASE);
            String allowed = "POST";
            if (!create) {
                String[] parts = request.getRequestURI().substring(BASE.length()).split("/", -1);
                if (parts.length < 2 || !parts[1].matches(GitHubAuthorizationQuery.UUID_PATTERN)) fail(GitHubAuthorizationError.INVALID_REQUEST);
                if (parts.length == 2) allowed = "DELETE";
                else if (parts.length == 3 && "installations".equals(parts[2])) allowed = "GET, HEAD";
                else if (parts.length == 5 && "installations".equals(parts[2]) && "repositories".equals(parts[4])) {
                    GitHubAuthorizationClient.validateInstallationId(parts[3]); allowed = "GET, HEAD";
                } else fail(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_NOT_FOUND);
            }
            request.setAttribute(GitHubAuthorizationProblems.ALLOW_ATTRIBUTE, allowed);
            if (!Set.of(allowed.split(", ")).contains(request.getMethod())) fail(GitHubAuthorizationError.METHOD_NOT_ALLOWED);
            if (create) {
                MediaType type;
                try { type = request.getContentType() == null ? null : MediaType.parseMediaType(request.getContentType()); }
                catch (IllegalArgumentException malformed) { fail(GitHubAuthorizationError.UNSUPPORTED_MEDIA_TYPE); return; }
                if (type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(type)) fail(GitHubAuthorizationError.UNSUPPORTED_MEDIA_TYPE);
                if (!config.getBrowserOrigin().equals(request.getHeader("Origin"))) fail(GitHubAuthorizationError.FORBIDDEN_SCOPE);
            } else noDeclaredBody(request);
            query(request, allowed.equals("GET, HEAD") ? Set.of("page") : Set.of(), 2048);
            if (request.getParameter("page") != null) {
                try { if (!request.getParameter("page").matches("[1-9][0-9]{0,9}") || Integer.parseInt(request.getParameter("page")) <= 0) fail(GitHubAuthorizationError.INVALID_REQUEST); }
                catch (NumberFormatException malformed) { fail(GitHubAuthorizationError.INVALID_REQUEST); }
            }
            String accept = request.getHeader("Accept");
            if (accept != null) {
                try { if (MediaType.parseMediaTypes(accept).stream().noneMatch(t -> t.getQualityValue() > 0 && t.isCompatibleWith(MediaType.APPLICATION_JSON))) fail(GitHubAuthorizationError.NOT_ACCEPTABLE); }
                catch (IllegalArgumentException malformed) { fail(GitHubAuthorizationError.NOT_ACCEPTABLE); }
            }
            if (create) {
                HttpServletRequest cached = body.json(request, response);
                if (cached != null) chain.doFilter(cached, response);
            } else if (body.ready(request, response)) chain.doFilter(request, response);
        } catch (GitHubAuthorizationException rejected) { problems.write(rejected.getError(), request, response); }
    }

    private static void noDeclaredBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) fail(GitHubAuthorizationError.INVALID_REQUEST);
    }
    private static void query(HttpServletRequest request, Set<String> allowed, int limit) {
        if (request.getQueryString() != null && request.getQueryString().getBytes(StandardCharsets.UTF_8).length > limit) fail(GitHubAuthorizationError.INVALID_REQUEST);
        request.getParameterMap().forEach((key, values) -> {
            if (!allowed.contains(key) || values.length != 1 || values[0].isEmpty()) fail(GitHubAuthorizationError.INVALID_REQUEST);
        });
    }
    private static void fail(GitHubAuthorizationError error) { throw new GitHubAuthorizationException(error); }
}
