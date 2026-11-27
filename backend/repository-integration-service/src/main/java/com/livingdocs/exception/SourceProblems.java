package com.livingdocs.exception;

import com.livingdocs.security.SourceCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Cùng một Problem cho MVC và lỗi ở Spring Security filter. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SourceProblems {
    private final JsonMapper json;

    public ProblemDetail body(SourceApiError error, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(error.getStatus()), error.getDetail());
        problem.setTitle(HttpStatus.valueOf(error.getStatus()).getReasonPhrase());
        problem.setType(URI.create("urn:livingdocs:problem:" + error.name().toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setInstance(URI.create("urn:uuid:" + UUID.randomUUID()));
        problem.setProperty("code", error.name());
        problem.setProperty("correlationId", request.getAttribute(SourceCorrelationFilter.ATTRIBUTE));
        log.atInfo().addKeyValue("code", error.name()).addKeyValue("httpStatus", error.getStatus())
                .addKeyValue("correlationId", request.getAttribute(SourceCorrelationFilter.ATTRIBUTE))
                .log("Source request rejected: {}", error.name());
        return problem;
    }

    public void write(SourceApiError error, HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(error.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (error == SourceApiError.INVALID_SERVICE_IDENTITY) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        json.writeValue(response.getOutputStream(), body(error, request));
    }
}
