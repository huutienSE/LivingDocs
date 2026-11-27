package com.livingdocs.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {
    private final SourceProblems problems;

    @ExceptionHandler(SourceApiException.class)
    public ResponseEntity<ProblemDetail> sourceFailure(SourceApiException failure, HttpServletRequest request) {
        return problem(failure.getError(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalid(Exception failure, HttpServletRequest request) {
        return problem(SourceApiError.INVALID_REQUEST, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> method(HttpServletRequest request) {
        return ResponseEntity.status(405).header("Allow", "POST").contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problems.body(SourceApiError.METHOD_NOT_ALLOWED, request));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> media(HttpServletRequest request) {
        return problem(SourceApiError.UNSUPPORTED_MEDIA_TYPE, request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ProblemDetail> accept(HttpServletRequest request) {
        return problem(SourceApiError.NOT_ACCEPTABLE, request);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ProblemDetail> missing(HttpServletRequest request) {
        return problem(SourceApiError.NOT_FOUND, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception failure, HttpServletRequest request) {
        return problem(SourceApiError.INTERNAL_ERROR, request);
    }

    private ResponseEntity<ProblemDetail> problem(SourceApiError error, HttpServletRequest request) {
        return ResponseEntity.status(error.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problems.body(error, request));
    }
}
