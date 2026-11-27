package com.livingdocs.document.exception;

import com.livingdocs.document.controller.ReviewContextController;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = ReviewContextController.class) @Order(-20) @RequiredArgsConstructor
public class ReviewContextExceptionHandler {
    private final ApiProblems problems;

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail persistence(DataAccessException error, HttpServletRequest request) {
        return problems.create(request, ReviewErrorCode.PERSISTENCE_ERROR, "The review context could not be read.");
    }
}
