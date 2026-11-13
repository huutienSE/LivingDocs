package com.livingdocs.document.api.controller;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.http.HttpStatusCode;

@Component
public class ApiProblems {
    private final JsonMapper json;
    public ApiProblems(JsonMapper json) { this.json = json; }

    public ProblemDetail create(HttpServletRequest request, int status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setType(URI.create("urn:livingdocs:problem:" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
        problem.setInstance(URI.create("urn:uuid:" + UUID.randomUUID()));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", request.getAttribute(RequestCorrelationFilter.ATTRIBUTE));
        return problem;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        json.writeValue(response.getOutputStream(), create(request, status, code, detail));
    }
}
