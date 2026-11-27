package com.livingdocs.security;

import com.livingdocs.exception.SourceApiError;
import com.livingdocs.exception.SourceApiException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Không redirect token qua HTTP; cấu hình cho phép HTTP chỉ phục vụ test/local được khai báo rõ. */
public final class SourceTransportFilter extends OncePerRequestFilter {
    private final boolean allowInsecure;

    public SourceTransportFilter(boolean allowInsecure) {
        this.allowInsecure = allowInsecure;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request.getServletPath().startsWith("/internal/v1/source/") && !allowInsecure && !request.isSecure()) {
            throw new SourceApiException(SourceApiError.SOURCE_UNAVAILABLE);
        }
        chain.doFilter(request, response);
    }
}
