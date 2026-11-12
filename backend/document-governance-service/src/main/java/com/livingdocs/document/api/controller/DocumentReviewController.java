package com.livingdocs.document.api.controller;

import com.livingdocs.document.api.request.ReviewRequest;
import com.livingdocs.document.api.response.ReviewResponse;
import com.livingdocs.document.security.ActorContextResolver;
import com.livingdocs.document.application.service.DocumentReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;

@RestController
@RequestMapping("/api/v1/document-versions")
public class DocumentReviewController {
    private final DocumentReviewService service;
    private final ActorContextResolver actors;

    public DocumentReviewController(DocumentReviewService service, ActorContextResolver actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping("/{versionId}/staff-decisions")
    public ResponseEntity<ReviewResponse> review(@PathVariable UUID versionId,
            @Valid @RequestBody ReviewRequest body, @RequestHeader(value = "If-Match", required = false) String etag,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        var actor = actors.resolve(jwt);
        return response(service.review(versionId, body, VersionEtags.require(versionId, etag), key, actor,
                (String) request.getAttribute(RequestCorrelationFilter.ATTRIBUTE)));
    }

    private ResponseEntity<ReviewResponse> response(ReviewResponse body) {
        return ResponseEntity.ok().eTag(VersionEtags.format(body.versionId(), body.revision()))
                .cacheControl(CacheControl.noStore()).body(body);
    }
}
