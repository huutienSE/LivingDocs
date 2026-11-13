package com.livingdocs.document.api.controller;

import com.livingdocs.document.domain.ReviewException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.*;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ReviewExceptionHandler {
    private final ApiProblems problems;
    public ReviewExceptionHandler(ApiProblems problems) { this.problems = problems; }

    @ExceptionHandler(ReviewException.class)
    public ProblemDetail business(ReviewException e, HttpServletRequest request) {
        return problems.create(request, e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, BindException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail invalid(Exception e, HttpServletRequest request) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ReviewException review) return business(review, request);
        }
        return problems.create(request, 400, "INVALID_REQUEST", "Request fields or parameters are invalid.");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail revision(Exception e, HttpServletRequest request) {
        return problems.create(request, 412, "REVISION_CONFLICT", "Reload the version before reviewing it.");
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ProblemDetail concurrent(Exception e, HttpServletRequest request) {
        return problems.create(request, 409, "CONCURRENT_REVIEW", "Retry the request with the same Idempotency-Key.");
    }

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail persistence(Exception e, HttpServletRequest request) {
        // Do not return SQL, constraint details or tenant data from an underlying database error.
        return problems.create(request, 500, "PERSISTENCE_ERROR", "Review could not be stored. No changes were committed.");
    }
}
