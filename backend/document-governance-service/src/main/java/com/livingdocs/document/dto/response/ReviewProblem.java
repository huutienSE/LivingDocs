package com.livingdocs.document.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import lombok.Getter;

/** Documents the ProblemDetail body emitted by MVC and security handlers. */
@Getter
@Schema(name = "Problem", requiredProperties = {"type", "title", "status", "detail", "instance", "code", "correlationId"})
public class ReviewProblem {
    private URI type;
    private String title;
    private int status;
    private String detail;
    private URI instance;
    @Schema(allowableValues = {"METHOD_NOT_ALLOWED", "NOT_ACCEPTABLE", "UNSUPPORTED_MEDIA_TYPE",
            "INVALID_REQUEST", "INVALID_IF_MATCH", "UNAUTHENTICATED", "REVIEW_FORBIDDEN", "AUTHORIZATION_UNAVAILABLE",
            "DOCUMENT_VERSION_NOT_FOUND", "STATE_TRANSITION_FORBIDDEN", "IDEMPOTENCY_CONFLICT",
            "CONCURRENT_REVIEW", "REVISION_CONFLICT", "RETURN_REASON_REQUIRED", "REVIEW_EVIDENCE_REQUIRED",
            "SOURCE_CHANGED_REVALIDATION_REQUIRED", "SOURCE_VALIDATION_UNAVAILABLE", "REVIEW_EVIDENCE_INVALID",
            "SOURCE_EVIDENCE_INSUFFICIENT", "PRECONDITION_REQUIRED", "PERSISTENCE_ERROR",
            "EVIDENCE_LIMIT_EXCEEDED", "DUPLICATE_EVIDENCE", "REPLAY_SNAPSHOT_UNAVAILABLE"})
    private String code;
    private String correlationId;
}
