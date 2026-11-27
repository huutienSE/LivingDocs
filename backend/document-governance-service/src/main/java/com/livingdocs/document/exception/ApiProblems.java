package com.livingdocs.document.exception;

import lombok.RequiredArgsConstructor;
import com.livingdocs.document.config.RequestCorrelationFilter;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class ApiProblems {
    private final JsonMapper json;

    public ProblemDetail create(HttpServletRequest request, ReviewErrorCode code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(code.getStatus(), detail);
        problem.setType(URI.create("urn:livingdocs:problem:" + code.getWireCode().toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
        problem.setInstance(URI.create("urn:uuid:" + UUID.randomUUID()));
        problem.setProperty("code", code.getWireCode());
        problem.setProperty("correlationId", request.getAttribute(RequestCorrelationFilter.ATTRIBUTE));
        return problem;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ReviewErrorCode code, String detail)
            throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType("application/problem+json");
        json.writeValue(response.getOutputStream(), create(request, code, detail));
    }
}
