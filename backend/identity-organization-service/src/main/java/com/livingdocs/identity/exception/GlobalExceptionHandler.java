package com.livingdocs.identity.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");

    @ExceptionHandler(IdentityAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> duplicateIdentity(
            IdentityAlreadyExistsException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, IdentityAlreadyExistsException.CODE,
                "Identity already exists", "An identity already exists for this email", request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ProblemDetail> invalidRequest(Exception ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "Invalid request", "Registration request is invalid", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> unsupportedMedia(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Unsupported media type", "Content-Type must be application/json", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> persistenceFailure(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        return internalError(request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception ex, HttpServletRequest request) {
        return internalError(request);
    }

    private ResponseEntity<ProblemDetail> internalError(HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Internal error", "The request could not be completed", request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String title,
            String detail, HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create("about:blank"));
        body.setTitle(title);
        body.setInstance(URI.create("urn:uuid:" + UUID.randomUUID()));
        body.setProperty("code", code);

        String incoming = request.getHeader("X-Correlation-ID");
        String correlationId = incoming != null && SAFE_CORRELATION_ID.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString();
        body.setProperty("correlationId", correlationId);

        return ResponseEntity.status(status).contentType(PROBLEM_JSON)
                .header("X-Correlation-ID", correlationId).body(body);
    }
}
