package com.livingdocs.document.exception;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.*;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import java.util.List;
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

    @ExceptionHandler({HttpMessageNotReadableException.class, BindException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class, ConstraintViolationException.class})
    public ProblemDetail invalid(Exception e, HttpServletRequest request) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ReviewException review) return business(review, request);
        }
        List<FieldError> fieldErrors = List.of();
        if (e instanceof BindException binding) {
            fieldErrors = binding.getFieldErrors("evidenceRefs");
        } else if (e instanceof HandlerMethodValidationException validation) {
            fieldErrors = validation.getParameterValidationResults().stream()
                    .filter(ParameterErrors.class::isInstance)
                    .map(ParameterErrors.class::cast)
                    .flatMap(errors -> errors.getFieldErrors("evidenceRefs").stream()).toList();
        }
        if (fieldErrors.stream().anyMatch(error -> "Size".equals(error.getCode()))) {
            return business(new ReviewEvidenceLimitExceededException(), request);
        }
        if (fieldErrors.stream().anyMatch(error -> "UniqueEvidenceRefs".equals(error.getCode()))) {
            return business(new DuplicateReviewEvidenceException(), request);
        }
        return problems.create(request, ReviewErrorCode.INVALID_REQUEST, "Request fields or parameters are invalid.");
    }

    @ExceptionHandler({HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class})
    public ResponseEntity<ProblemDetail> transport(Exception e, HttpServletRequest request) {
        var error = (ErrorResponse) e;
        var code = switch (error.getStatusCode().value()) {
            case 405 -> ReviewErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ReviewErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> ReviewErrorCode.NOT_ACCEPTABLE;
        };
        return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problems.create(request, code, code.getStatus().getReasonPhrase()));
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
