package com.livingdocs.document.exception;
import com.livingdocs.document.controller.DocumentSubmitController;
import com.livingdocs.document.config.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
/** Advice applies only to API-113; existing API-120 behavior is untouched. */
@RestControllerAdvice(assignableTypes=DocumentSubmitController.class) @Order(-10) @RequiredArgsConstructor
public class SubmitExceptionHandler {
    private final ApiProblems problems;
    @ExceptionHandler(ReviewException.class) public ProblemDetail shared(ReviewException e,HttpServletRequest r) { return problems.create(r,e.getErrorCode(),e.getMessage()); }
    @ExceptionHandler(SubmitException.class)
    public ResponseEntity<ProblemDetail> submit(SubmitException e, HttpServletRequest r) {
        var response = ResponseEntity.status(e.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (e.getStatus() == HttpStatus.UNAUTHORIZED) response.header("WWW-Authenticate", "Bearer");
        return response.body(problem(e.getStatus(), e.getCode(), e.getMessage(), r));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,BindException.class,HandlerMethodValidationException.class})
    public ProblemDetail invalid(Exception e,HttpServletRequest r) { return problem(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","Invalid submit fields or headers.",r); }
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail revision(Exception e, HttpServletRequest r) {
        return problems.create(r, ReviewErrorCode.REVISION_CONFLICT, "Reload the version before submitting it.");
    }
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ProblemDetail concurrent(Exception e, HttpServletRequest r) {
        return problems.create(r, ReviewErrorCode.CONCURRENT_REVIEW, "Retry the request with the same Idempotency-Key.");
    }
    @ExceptionHandler(DataAccessException.class) public ProblemDetail persistence(Exception e,HttpServletRequest r) { return problem(HttpStatus.INTERNAL_SERVER_ERROR,"PERSISTENCE_ERROR","Submission could not be stored. No changes were committed.",r); }
    private ProblemDetail problem(HttpStatus status,String code,String detail,HttpServletRequest r) {
        var p=ProblemDetail.forStatusAndDetail(status,detail); p.setType(URI.create("urn:livingdocs:problem:"+code.toLowerCase(java.util.Locale.ROOT).replace('_','-'))); p.setInstance(URI.create("urn:uuid:"+UUID.randomUUID())); p.setProperty("code",code); p.setProperty("correlationId",r.getAttribute(RequestCorrelationFilter.ATTRIBUTE)); return p;
    }
}
