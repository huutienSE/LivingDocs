package com.livingdocs.document.controller;

import com.livingdocs.document.dto.response.*;
import com.livingdocs.document.config.RequestCorrelationFilter;
import com.livingdocs.document.exception.ReviewErrorCode;
import com.livingdocs.document.exception.ReviewRequestException;
import com.livingdocs.document.model.SuccessCode;
import com.livingdocs.document.security.StaffReadAccessResolver;
import com.livingdocs.document.service.ReviewContextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@RestController @RequestMapping("/api/v1/document-versions") @RequiredArgsConstructor
public class ReviewContextController {
    private final ReviewContextService service;
    private final StaffReadAccessResolver authority;
    private final JsonMapper json;

    @GetMapping(value = "/{versionId}/review-context", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "api116_get", summary = "Canonical Staff review context and representation ETag")
    @Parameter(name = "If-None-Match", in = ParameterIn.HEADER, required = false,
            description = "Complete context validator or standalone wildcard; scope authorization is still performed before 304.", schema = @Schema(type = "string"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scoped canonical review context", useReturnTypeSchema = true,
                headers = {@Header(name = "ETag", schema = @Schema(type = "string")),
                    @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
                    @Header(name = "X-Correlation-ID", schema = @Schema(type = "string"))}),
        @ApiResponse(responseCode = "304", description = "Complete context ETag matches or wildcard selects an existing scoped context; authorization still checked", content = @Content,
                headers = {@Header(name = "ETag", schema = @Schema(type = "string")),
                    @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
                    @Header(name = "X-Correlation-ID", schema = @Schema(type = "string"))}),
        @ApiResponse(responseCode = "400", description = "Invalid version ID or unsupported query parameters", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
        @ApiResponse(responseCode = "401", description = "JWT missing or invalid", headers = @Header(name = "WWW-Authenticate", schema = @Schema(type = "string", allowableValues = "Bearer")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
        @ApiResponse(responseCode = "403", description = "Staff read permission missing", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
        @ApiResponse(responseCode = "404", description = "Version absent or inaccessible resource/template scope", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
        @ApiResponse(responseCode = "405", description = "Only GET supported", headers = @Header(name = "Allow", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
        @ApiResponse(responseCode = "406", description = "Unsupported response media type", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
        @ApiResponse(responseCode = "500", description = "Context persistence failure or query timeout", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class))),
        @ApiResponse(responseCode = "503", description = "S01 authority unavailable or malformed", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ReviewProblem.class)))
    })
    public ResponseEntity<SuccessResponse<ReviewContextView>> load(@PathVariable UUID versionId,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty())
            throw new ReviewRequestException(ReviewErrorCode.INVALID_REQUEST, "Review context does not accept query parameters.");
        var context = service.load(versionId, authority.resolve(jwt, (String) request.getAttribute(RequestCorrelationFilter.ATTRIBUTE)));
        var body = SuccessResponse.of(SuccessCode.REVIEW_CONTEXT_LOADED, context);
        // MVC uses this same configured mapper. Include all response data, not just the version row.
        String etag = VersionEtags.formatContext(context.getVersion().getVersionId(), context.getVersion().getRevision(), json.writeValueAsBytes(body));
        String validator = request.getHeader(HttpHeaders.IF_NONE_MATCH);
        // Spring's safe-method comparison does not match a standalone wildcard in this version.
        // The context has already passed authentication, permission and resource-scope checks.
        if (validator != null && "*".equals(validator.trim()))
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().eTag(etag).cacheControl(CacheControl.noStore()).body(body);
    }
}
