package com.livingdocs.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SourceCorrelationFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = SourceCorrelationFilter.class.getName() + ".correlationId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private final HandlerExceptionResolver exceptionResolver;

    public SourceCorrelationFilter(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Correlation-ID");
        String id = supplied != null && SAFE.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader("X-Correlation-ID", id);
        response.setHeader("Cache-Control", "no-store");
        String previous = MDC.get("correlationId");
        MDC.put("correlationId", id);
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException failure) {
            // Chuyển lỗi ngoài MVC vào GlobalExceptionHandler khi phản hồi chưa được gửi.
            if (response.isCommitted() || !request.getServletPath().startsWith("/internal/v1/source/")) {
                throw failure;
            }
            response.resetBuffer();
            if (exceptionResolver.resolveException(request, response, null, failure) == null) {
                throw failure;
            }
        } finally {
            if (previous == null) MDC.remove("correlationId");
            else MDC.put("correlationId", previous);
        }
    }
}
