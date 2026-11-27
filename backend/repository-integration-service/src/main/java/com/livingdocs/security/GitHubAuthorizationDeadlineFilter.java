package com.livingdocs.security;

import com.livingdocs.exception.GitHubAuthorizationProblems;
import com.livingdocs.model.GitHubAuthorizationError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.web.filter.OncePerRequestFilter;

public final class GitHubAuthorizationDeadlineFilter extends OncePerRequestFilter {
    private static final String ATTRIBUTE = GitHubAuthorizationDeadlineFilter.class.getName() + ".deadline";
    private final GitHubAuthorizationProblems problems;
    public GitHubAuthorizationDeadlineFilter(GitHubAuthorizationProblems problems) { this.problems = problems; }
    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(request.getAttribute(ATTRIBUTE) instanceof Long)) request.setAttribute(ATTRIBUTE, System.nanoTime() + Duration.ofSeconds(8).toNanos());
        if (System.nanoTime() >= deadline(request)) { problems.write(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_UNAVAILABLE, request, response); return; }
        chain.doFilter(request, response);
    }
    public static long deadline(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof Long deadline ? deadline : System.nanoTime() + Duration.ofSeconds(8).toNanos();
    }
}
