package com.livingdocs.document.controller;

import lombok.RequiredArgsConstructor;
import com.livingdocs.document.config.RequestCorrelationFilter;

import com.livingdocs.document.dto.request.ReviewRequest;
import com.livingdocs.document.dto.response.ReviewResponse;
import com.livingdocs.document.dto.response.SuccessResponse;
import com.livingdocs.document.model.StaffReviewResult;
import com.livingdocs.document.mapper.StaffDecisionMapper;
import com.livingdocs.document.dto.response.ReviewProblem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import com.livingdocs.document.security.ActorContextResolver;
import com.livingdocs.document.service.DocumentReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;

@RestController
@RequestMapping("/api/v1/document-versions")
@RequiredArgsConstructor
public class DocumentReviewController {
    private final DocumentReviewService service;
    private final ActorContextResolver actors;
    private final StaffDecisionMapper mapper;

    @PostMapping(value = "/{versionId}/staff-decisions", produces = "application/json")
    @Operation(operationId = "recordStaffDecision", summary = "PASS to Manager Approval or return the same version to Draft",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReviewRequest.class))))
    @Parameter(name = "X-Correlation-ID", in = ParameterIn.HEADER, required = false,
            description = "1-64 ASCII letters, digits, dots, underscores or hyphens, starting with a letter or digit; otherwise a UUID is generated.",
            schema = @Schema(type = "string"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Persisted review state; original response for exact replay.",
                    headers = {@Header(name = "ETag", schema = @Schema(type = "string")),
                            @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")),
                            @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store"))},
                    useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Invalid request", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "401", description = "Unauthenticated", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "404", description = "Version not found", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "405", description = "Method not allowed", headers = {@Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), @Header(name = "Allow", schema = @Schema(type = "string"))}, content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "406", description = "Response media type not acceptable", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "415", description = "Unsupported request media type", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "409", description = "State, source or idempotency conflict", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "412", description = "Revision conflict", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "422", description = "Invalid review evidence or feedback", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "428", description = "If-Match required", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "500", description = "Persistence failure", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
            @ApiResponse(responseCode = "503", description = "S01 authorization or source validation unavailable", headers = @Header(name = "X-Correlation-ID", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class)))
    })
    public ResponseEntity<SuccessResponse<ReviewResponse>> review(@PathVariable UUID versionId,
            @Valid @RequestBody ReviewRequest body,
            @Parameter(required = true, description = "Exact strong version ETag; missing returns 428.")
            @RequestHeader(value = "If-Match", required = false) String etag,
            @Parameter(required = true, schema = @Schema(pattern = "^[A-Za-z0-9._:-]{1,128}$"))
            @NotNull @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$")
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        var actor = actors.resolve(jwt);
        return response(service.review(versionId, mapper.toCommand(body), VersionEtags.require(versionId, etag), key, actor,
                (String) request.getAttribute(RequestCorrelationFilter.ATTRIBUTE)));
    }

    private ResponseEntity<SuccessResponse<ReviewResponse>> response(StaffReviewResult result) {
        var body = SuccessResponse.of(result.receipt(), mapper.toResponse(result));
        return ResponseEntity.ok().eTag(VersionEtags.format(body.getData().getVersionId(), body.getData().getRevision()))
                .cacheControl(CacheControl.noStore()).body(body);
    }
}
