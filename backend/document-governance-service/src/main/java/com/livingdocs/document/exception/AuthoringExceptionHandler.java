package com.livingdocs.document.exception;

import com.livingdocs.document.controller.DocumentAuthoringController;
import com.livingdocs.document.config.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = DocumentAuthoringController.class)
@Order(-10)
public class AuthoringExceptionHandler {
    @ExceptionHandler(AuthoringException.class)
    public ResponseEntity<ProblemDetail> authoring(AuthoringException exception, HttpServletRequest request) {
        return problem(exception.getStatus(), exception.getCode(), exception.getMessage(), request);
    }
    @ExceptionHandler(S01AuthorityException.class)
    public ResponseEntity<ProblemDetail> authority(S01AuthorityException exception, HttpServletRequest request) {
        return switch (exception.getKind()) {
            case UNAUTHENTICATED -> problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid S01 token is required.", request);
            case FORBIDDEN -> problem(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Permission in the resource scope is required.", request);
            case UNAVAILABLE -> problem(HttpStatus.SERVICE_UNAVAILABLE, "AUTHORIZATION_UNAVAILABLE", "Current authorization could not be verified.", request);
        };
    }
    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String message, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, message);
        problem.setType(URI.create("urn:livingdocs:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", request.getAttribute(RequestCorrelationFilter.ATTRIBUTE));
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status == HttpStatus.UNAUTHORIZED) response.header("WWW-Authenticate", "Bearer");
        return response.body(problem);
    }
}
