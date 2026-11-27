package com.livingdocs.document.controller;

import com.livingdocs.document.client.S01ApprovalAuthorizationClient;
import com.livingdocs.document.dto.response.ApprovalContextResponse;
import com.livingdocs.document.dto.response.ApprovalQueueResponse;
import com.livingdocs.document.dto.response.ReviewProblem;
import com.livingdocs.document.dto.response.SuccessResponse;
import com.livingdocs.document.exception.ReviewErrorCode;
import com.livingdocs.document.exception.ReviewRequestException;
import com.livingdocs.document.mapper.ApprovalQueryMapper;
import com.livingdocs.document.model.SuccessCode;
import com.livingdocs.document.service.DocumentApprovalQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API-122 Manager approval queue and API-123 approval context (read-only, no state change). */
@RestController
public class ApprovalQueryController {
    private final DocumentApprovalQueryService service;
    private final S01ApprovalAuthorizationClient authority;
    private final ApprovalQueryMapper mapper;
    private final int maxPageSize;

    public ApprovalQueryController(DocumentApprovalQueryService service, S01ApprovalAuthorizationClient authority,
                                   ApprovalQueryMapper mapper,
                                   @Value("${livingdocs.approval.queue.max-page-size:100}") int maxPageSize) {
        this.service = service;
        this.authority = authority;
        this.mapper = mapper;
        this.maxPageSize = maxPageSize;
    }

    @GetMapping(value = "/api/v1/approval-queue", produces = "application/json")
    @Operation(operationId = "getApprovalQueue", summary = "API-122 Manager approval queue",
            description = "Versions at IN_REVIEW / MANAGER_APPROVAL inside the caller's MANAGER + DOCUMENT_FINAL_APPROVE "
                    + "workspace/repository grants (S01 API-038). Oldest Staff PASS first. Read-only.")
    @Parameter(name = "X-Correlation-ID", in = ParameterIn.HEADER, required = false,
            description = "1-64 ASCII letters, digits, dots, underscores or hyphens, starting with a letter or digit; otherwise a UUID is generated.",
            schema = @Schema(type = "string"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Queue page",
                    headers = {@Header(name = "X-Correlation-ID", schema = @Schema(type = "string")),
                            @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store"))},
                    useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Invalid page or size", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "401", description = "Unauthenticated", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "403", description = "No Manager approval grant", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "406", description = "Response media type not acceptable", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "500", description = "Persistence failure", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "503", description = "S01 authorization unavailable", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class)))
    })
    public ResponseEntity<SuccessResponse<ApprovalQueueResponse>> queue(
            @Parameter(description = "Zero-based page index.") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size, 1 to the configured maximum (default 100).")
            @RequestParam(defaultValue = "20") @Min(1) int size,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        if (size > maxPageSize) {
            throw new ReviewRequestException(ReviewErrorCode.INVALID_REQUEST, "size must not exceed " + maxPageSize + ".");
        }
        var actor = authority.resolve(jwt);
        var body = SuccessResponse.of(SuccessCode.APPROVAL_QUEUE_LOADED, mapper.toResponse(service.queue(actor, page, size)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping(value = "/api/v1/document-versions/{versionId}/approval-context", produces = "application/json")
    @Operation(operationId = "getApprovalContext", summary = "API-123 Manager approval context and ETag",
            description = "Version, current content reference, Staff and Manager decision history and evidence. "
                    + "Returns the real state for any version in scope; awaitingManagerDecision is true only at "
                    + "IN_REVIEW / MANAGER_APPROVAL. The ETag is the If-Match value for API-124. Read-only.")
    @Parameter(name = "X-Correlation-ID", in = ParameterIn.HEADER, required = false,
            description = "1-64 ASCII letters, digits, dots, underscores or hyphens, starting with a letter or digit; otherwise a UUID is generated.",
            schema = @Schema(type = "string"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Approval context",
                    headers = {@Header(name = "ETag", schema = @Schema(type = "string")),
                            @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")),
                            @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store"))},
                    useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Invalid version id", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "401", description = "Unauthenticated", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "403", description = "Version outside the Manager's workspace/repository scope", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "404", description = "Version not found in the caller's tenant", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "406", description = "Response media type not acceptable", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "500", description = "Persistence failure", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "503", description = "S01 authorization unavailable", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class)))
    })
    public ResponseEntity<SuccessResponse<ApprovalContextResponse>> context(@PathVariable UUID versionId,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        var actor = authority.resolve(jwt);
        var context = service.context(versionId, actor);
        var body = SuccessResponse.of(SuccessCode.APPROVAL_CONTEXT_LOADED, mapper.toResponse(context));
        return ResponseEntity.ok().eTag(VersionEtags.format(context.version().versionId(), context.version().revision()))
                .cacheControl(CacheControl.noStore()).body(body);
    }
}
