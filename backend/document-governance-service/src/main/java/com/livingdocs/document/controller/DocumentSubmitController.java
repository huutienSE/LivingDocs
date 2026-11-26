package com.livingdocs.document.controller;
import com.livingdocs.document.client.S01SubmitAuthorizationClient;
import com.livingdocs.document.config.RequestCorrelationFilter;
import com.livingdocs.document.dto.request.SubmitReviewRequest;
import com.livingdocs.document.dto.response.*;
import com.livingdocs.document.mapper.SubmitMapper;
import com.livingdocs.document.model.SubmitCommand;
import com.livingdocs.document.service.DocumentSubmitService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/document-versions") @RequiredArgsConstructor
public class DocumentSubmitController {
    private final DocumentSubmitService service;
    private final S01SubmitAuthorizationClient authority;
    private final SubmitMapper mapper;
    @PostMapping(value="/{versionId}/submit", consumes="application/json", produces="application/json")
    @Operation(operationId="api113_post", summary="Submit a Draft for mandatory Staff Review")
    @ApiResponses({
        @ApiResponse(responseCode="200",description="Persisted submission or original replay receipt",useReturnTypeSchema=true,
            headers={@Header(name="ETag",schema=@Schema(type="string")),
                @Header(name="Cache-Control",schema=@Schema(type="string",allowableValues="no-store")),
                @Header(name="X-Correlation-ID",schema=@Schema(type="string"))}),
        @ApiResponse(responseCode="400",description="Invalid request",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=SubmitProblem.class))),
        @ApiResponse(responseCode="401",description="Invalid token",headers=@Header(name="WWW-Authenticate",schema=@Schema(type="string",allowableValues="Bearer")),content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=SubmitProblem.class))),
        @ApiResponse(responseCode="403",description="Insufficient scope",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=SubmitProblem.class))),
        @ApiResponse(responseCode="404",description="Version not found",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=SubmitProblem.class))),
        @ApiResponse(responseCode="405",description="Method not allowed",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=SubmitProblem.class))),
        @ApiResponse(responseCode="406",description="Response media type not acceptable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=SubmitProblem.class))),
        @ApiResponse(responseCode="409",description="State/idempotency conflict",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=SubmitProblem.class))),
        @ApiResponse(responseCode="412",description="Stale ETag",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=SubmitProblem.class))),
        @ApiResponse(responseCode="415",description="Request media type not supported",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=SubmitProblem.class))),
        @ApiResponse(responseCode="422",description="Missing evidence/binding",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=SubmitProblem.class))),
        @ApiResponse(responseCode="428",description="If-Match required",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=SubmitProblem.class))),
        @ApiResponse(responseCode="500",description="Persistence failure; rolled back",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=SubmitProblem.class))),
        @ApiResponse(responseCode="503",description="Authorization authority unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=SubmitProblem.class)))})
    public ResponseEntity<SuccessResponse<ReviewResponse>> submit(@PathVariable UUID versionId, @Valid @RequestBody SubmitReviewRequest body,
            @Parameter(required=true) @RequestHeader(value="If-Match",required=false) String etag,
            @Parameter(required=true) @NotNull @Pattern(regexp="^[A-Za-z0-9._:-]{1,128}$") @RequestHeader(value="Idempotency-Key",required=false) String key,
            @Parameter(hidden=true) @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        long revision=VersionEtags.require(versionId,etag);
        var actor=authority.resolve(jwt);
        var result=service.submit(versionId,new SubmitCommand(body.getNote()),revision,key,actor,(String)request.getAttribute(RequestCorrelationFilter.ATTRIBUTE));
        return ResponseEntity.ok().eTag(VersionEtags.format(versionId,result.version().revision())).cacheControl(CacheControl.noStore()).body(SuccessResponse.of(result.code(),result.message(),mapper.toResponse(result)));
    }
}
