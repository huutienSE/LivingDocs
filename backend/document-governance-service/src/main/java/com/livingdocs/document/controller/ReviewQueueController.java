package com.livingdocs.document.controller;

import com.livingdocs.document.dto.request.ReviewQueueQuery;
import com.livingdocs.document.dto.response.*;
import com.livingdocs.document.exception.InvalidReviewQueueQueryException;
import com.livingdocs.document.model.SuccessCode;
import com.livingdocs.document.security.ReviewQueueAccessResolver;
import com.livingdocs.document.service.ReviewQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/review-queue") @RequiredArgsConstructor
public class ReviewQueueController {
    private static final Set<String> PARAMETERS = Set.of("page", "size", "workspaceId", "repositoryConnectionId",
            "templateId", "driftSeverity", "submittedFrom", "submittedTo", "sort", "direction");
    private final ReviewQueueService service;
    private final ReviewQueueAccessResolver authority;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "api115_get", summary = "Staff Review Queue within current granted scope")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scoped queue, including an empty page", useReturnTypeSchema = true,
            headers = {@Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
                @Header(name = "X-Correlation-ID", schema = @Schema(type = "string"))}),
        @ApiResponse(responseCode = "400", description = "Invalid filters or configured query budget exceeded", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
        @ApiResponse(responseCode = "401", description = "JWT missing or invalid", headers = @Header(name = "WWW-Authenticate", schema = @Schema(type = "string", allowableValues = "Bearer")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
        @ApiResponse(responseCode = "403", description = "Staff read permission or requested scope missing", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
        @ApiResponse(responseCode = "405", description = "Only GET is supported", headers = @Header(name = "Allow", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
        @ApiResponse(responseCode = "406", description = "Unsupported response media type", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
        @ApiResponse(responseCode = "500", description = "Queue persistence unavailable", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
        @ApiResponse(responseCode = "503", description = "S01 authority unavailable or malformed", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class)))
    })
    public ResponseEntity<SuccessResponse<ReviewQueueView>> list(@AuthenticationPrincipal Jwt jwt,
            @Valid @ParameterObject @ModelAttribute ReviewQueueQuery query, HttpServletRequest request) {
        if (request.getParameterMap().entrySet().stream().anyMatch(e -> !PARAMETERS.contains(e.getKey())
                || e.getValue().length != 1 || e.getValue()[0].isBlank()))
            throw new InvalidReviewQueueQueryException();
        var result = service.list(authority.resolve(jwt), query);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(SuccessCode.REVIEW_QUEUE_LOADED, result));
    }
}
