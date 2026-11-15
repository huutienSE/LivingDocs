package com.livingdocs.document.exception;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.*;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
@RequiredArgsConstructor
public class ReviewExceptionHandler {
    private final ApiProblems problems;

    @ExceptionHandler(ReviewException.class)
    public ProblemDetail business(ReviewException e, HttpServletRequest request) {
        return problems.create(request, e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, BindException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail invalid(Exception e, HttpServletRequest request) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ReviewException review) return business(review, request);
        }
        return problems.create(request, ReviewErrorCode.INVALID_REQUEST, "Request fields or parameters are invalid.");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail revision(Exception e, HttpServletRequest request) {
        return problems.create(request, ReviewErrorCode.REVISION_CONFLICT, "Reload the version before reviewing it.");
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ProblemDetail concurrent(Exception e, HttpServletRequest request) {
        return problems.create(request, ReviewErrorCode.CONCURRENT_REVIEW, "Retry the request with the same Idempotency-Key.");
    }

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail persistence(Exception e, HttpServletRequest request) {
        // Do not return SQL, constraint details or tenant data from an underlying database error.
        return problems.create(request, ReviewErrorCode.PERSISTENCE_ERROR, "Review could not be stored. No changes were committed.");
    }
}
