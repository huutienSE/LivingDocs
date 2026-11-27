package com.livingdocs.document.exception;

import com.livingdocs.document.controller.ReviewQueueController;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = ReviewQueueController.class) @Order(-20) @RequiredArgsConstructor
public class ReviewQueueExceptionHandler {
    private final ApiProblems problems;

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail persistence(DataAccessException failure, HttpServletRequest request) {
        return problems.create(request, ReviewErrorCode.PERSISTENCE_ERROR, "The review queue could not be read.");
    }
}
