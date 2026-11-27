package com.livingdocs.security;

import com.livingdocs.config.GitHubAuthorizationProperties;
import com.livingdocs.client.GitHubAuthorizationIo;
import com.livingdocs.exception.GitHubAuthorizationException;
import com.livingdocs.exception.GitHubAuthorizationProblems;
import com.livingdocs.dto.request.GitHubAuthorizationQuery;
import com.livingdocs.model.GitHubAuthorizationError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.*;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public final class GitHubAuthorizationRequestFilter extends OncePerRequestFilter {
    private static final Set<String> QUERY = Set.of("workspaceId","authorizationContextId","installationId","installationPage");
    private final GitHubAuthorizationProblems problems;
    private final GitHubAuthorizationProperties config;
    private final GitHubAuthorizationBodyGuard body;
    public GitHubAuthorizationRequestFilter(GitHubAuthorizationProblems problems, GitHubAuthorizationProperties config, GitHubAuthorizationIo io) {
        this.problems = problems; this.config = config;
        this.body = new GitHubAuthorizationBodyGuard(problems, io);
    }
    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/api/v1/integrations/github/authorization".equals(request.getRequestURI());
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (!config.isAllowLocalHttp() && !req.isSecure()) fail(GitHubAuthorizationError.USER_TOKEN_TRUST_UNAVAILABLE);
            if (!Set.of("GET","HEAD").contains(req.getMethod())) fail(GitHubAuthorizationError.METHOD_NOT_ALLOWED);
            if (req.getContentLengthLong() > 0 || req.getHeader("Transfer-Encoding") != null) fail(GitHubAuthorizationError.INVALID_REQUEST);
            if (req.getQueryString() != null && req.getQueryString().getBytes(StandardCharsets.UTF_8).length > 2048) fail(GitHubAuthorizationError.INVALID_REQUEST);
            req.getParameterMap().forEach((key, values) -> {
                if (!QUERY.contains(key) || values.length != 1 || values[0].isEmpty()) fail(GitHubAuthorizationError.INVALID_REQUEST);
            });
            if (req.getParameter("workspaceId") == null || !req.getParameter("workspaceId").matches(GitHubAuthorizationQuery.UUID_PATTERN))
                fail(GitHubAuthorizationError.INVALID_REQUEST);
            String context = req.getParameter("authorizationContextId");
            if (context != null && !context.matches(GitHubAuthorizationQuery.UUID_PATTERN)) fail(GitHubAuthorizationError.INVALID_REQUEST);
            String installation = req.getParameter("installationId"), page = req.getParameter("installationPage");
            if ((installation != null && context == null) || (page != null && installation == null)) fail(GitHubAuthorizationError.INVALID_REQUEST);
            try {
                if (installation != null && (!installation.matches("[1-9][0-9]{0,18}") || Long.parseLong(installation) <= 0)) fail(GitHubAuthorizationError.INVALID_REQUEST);
                if (page != null && (!page.matches("[1-9][0-9]{0,9}") || Integer.parseInt(page) <= 0)) fail(GitHubAuthorizationError.INVALID_REQUEST);
            } catch (NumberFormatException e) { fail(GitHubAuthorizationError.INVALID_REQUEST); }
            String accept = req.getHeader("Accept");
            if (accept != null) {
                List<MediaType> types;
                try { types = MediaType.parseMediaTypes(accept); } catch (IllegalArgumentException e) { fail(GitHubAuthorizationError.NOT_ACCEPTABLE); return; }
                if (types.stream().noneMatch(t -> t.getQualityValue() > 0 && t.isCompatibleWith(MediaType.APPLICATION_JSON))) fail(GitHubAuthorizationError.NOT_ACCEPTABLE);
            }
            if (body.ready(req, res)) chain.doFilter(req,res);
        } catch (GitHubAuthorizationException e) { problems.write(e.getError(), req,res); }
    }
    private static void fail(GitHubAuthorizationError error) { throw new GitHubAuthorizationException(error); }
}
