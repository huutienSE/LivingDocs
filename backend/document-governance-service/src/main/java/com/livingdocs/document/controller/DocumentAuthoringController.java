package com.livingdocs.document.controller;

import com.livingdocs.document.client.S01DocumentAuthorizationClient;
import com.livingdocs.document.config.RequestCorrelationFilter;
import com.livingdocs.document.dto.request.*;
import com.livingdocs.document.dto.response.*;
import com.livingdocs.document.mapper.DocumentAuthoringMapper;
import com.livingdocs.document.model.SuccessCode;
import com.livingdocs.document.service.DocumentAuthoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = AuthoringProblem.class))),
    @ApiResponse(responseCode = "401", description = "Authentication required", headers = @Header(name = "WWW-Authenticate", schema = @Schema(type = "string")), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = AuthoringProblem.class))),
    @ApiResponse(responseCode = "403", description = "Permission denied", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = AuthoringProblem.class))),
    @ApiResponse(responseCode = "404", description = "Resource not found", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = AuthoringProblem.class))),
    @ApiResponse(responseCode = "409", description = "Resource state or concurrent write conflict", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = AuthoringProblem.class))),
    @ApiResponse(responseCode = "412", description = "Stale or changed resource", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = AuthoringProblem.class))),
    @ApiResponse(responseCode = "428", description = "If-Match required", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = AuthoringProblem.class))),
    @ApiResponse(responseCode = "500", description = "Write rolled back", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = AuthoringProblem.class))),
    @ApiResponse(responseCode = "503", description = "Authority or source snapshot unavailable", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = AuthoringProblem.class)))
})
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@RestController
@RequiredArgsConstructor
public class DocumentAuthoringController {
    private final DocumentAuthoringService service;
    private final S01DocumentAuthorizationClient authority;
    private final DocumentAuthoringMapper mapper;

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(value = "/api/v1/workspaces/{workspaceId}/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "api100_post", summary = "Create a Document")
    public ResponseEntity<SuccessResponse<DocumentView>> createDocument(@PathVariable UUID workspaceId,
            @Valid @RequestBody DocumentCreateRequest body, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        var result = service.createDocument(workspaceId, body.getCollectionId(), body.getRepositoryConnectionId(),
                body.getTitle(), authority.resolve(jwt), jwt.getTokenValue(), correlation(request));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(SuccessCode.DOCUMENT_CREATED, mapper.document(result)));
    }
    @ApiResponse(responseCode = "200", description = "Successful result")
    @GetMapping("/api/v1/documents/{documentId}")
    @Operation(operationId = "api101_get", summary = "Read a Document and its latest version reference")
    public ResponseEntity<SuccessResponse<DocumentView>> document(@PathVariable UUID documentId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SuccessResponse.of(
                SuccessCode.DOCUMENT_LOADED, mapper.document(service.document(documentId, authority.resolve(jwt)))));
    }
    @ApiResponse(responseCode = "200", description = "Successful result")
    @GetMapping("/api/v1/documents/{documentId}/versions")
    @Operation(operationId = "api104_get", summary = "List Document Versions")
    public ResponseEntity<SuccessResponse<DocumentVersionsView>> versions(@PathVariable UUID documentId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SuccessResponse.of(
                SuccessCode.DOCUMENT_VERSIONS_LOADED, mapper.page(service.versions(documentId, page, size, authority.resolve(jwt)))));
    }
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "Draft created", headers = @Header(name = "ETag", schema = @Schema(type = "string")))
    @PostMapping(value = "/api/v1/documents/{documentId}/versions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "api105_post", summary = "Create a manual Draft with trusted source evidence")
    public ResponseEntity<SuccessResponse<AuthoringVersionView>> createDraft(@PathVariable UUID documentId,
            @Valid @RequestBody DraftCreateRequest body, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        var result = service.createDraft(documentId, body.getContent(), body.getSourceSnapshotId(),
                authority.resolve(jwt), jwt.getTokenValue(), correlation(request));
        return ResponseEntity.status(201).eTag(VersionEtags.format(result.versionId(), result.revision()))
                .cacheControl(CacheControl.noStore()).body(SuccessResponse.of(SuccessCode.DOCUMENT_VERSION_CREATED, mapper.version(result)));
    }
    @ApiResponse(responseCode = "200", description = "Version result", headers = @Header(name = "ETag", schema = @Schema(type = "string")))
    @GetMapping("/api/v1/document-versions/{versionId}")
    @Operation(operationId = "api106_get", summary = "Read an exact Document Version and its Markdown")
    public ResponseEntity<SuccessResponse<AuthoringVersionView>> version(@PathVariable UUID versionId, @AuthenticationPrincipal Jwt jwt) {
        var result = service.version(versionId, authority.resolve(jwt));
        return ResponseEntity.ok().eTag(VersionEtags.format(versionId, result.revision())).cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(SuccessCode.DOCUMENT_VERSION_LOADED, mapper.version(result)));
    }
    @ApiResponse(responseCode = "200", description = "Version result", headers = @Header(name = "ETag", schema = @Schema(type = "string")))
    @PatchMapping(value = "/api/v1/document-versions/{versionId}/content", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "api107_patch", summary = "Replace Draft Markdown")
    public ResponseEntity<SuccessResponse<AuthoringVersionView>> edit(@PathVariable UUID versionId,
            @Valid @RequestBody DraftContentRequest body,
            @Parameter(required = true) @RequestHeader(value = "If-Match", required = false) String etag,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        long revision = VersionEtags.require(versionId, etag);
        var result = service.edit(versionId, body.getContent(), revision, authority.resolve(jwt), jwt.getTokenValue(), correlation(request));
        return ResponseEntity.ok().eTag(VersionEtags.format(versionId, result.revision())).cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(SuccessCode.DOCUMENT_CONTENT_UPDATED, mapper.version(result)));
    }
    @ApiResponse(responseCode = "200", description = "Successful result")
    @GetMapping("/api/v1/document-versions/{versionId}/evidence")
    @Operation(operationId = "api108_get", summary = "Read persisted version evidence")
    public ResponseEntity<SuccessResponse<DocumentEvidenceView>> evidence(@PathVariable UUID versionId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SuccessResponse.of(
                SuccessCode.DOCUMENT_EVIDENCE_LOADED, mapper.evidence(service.evidence(versionId, authority.resolve(jwt)))));
    }
    private String correlation(HttpServletRequest request) {
        return (String) request.getAttribute(RequestCorrelationFilter.ATTRIBUTE);
    }
}
