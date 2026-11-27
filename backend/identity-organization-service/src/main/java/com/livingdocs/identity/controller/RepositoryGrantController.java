package com.livingdocs.identity.controller;

import com.livingdocs.identity.dto.request.RepositoryGrantRequest;
import com.livingdocs.identity.dto.response.RepositoryGrantResponse;
import com.livingdocs.identity.dto.response.SuccessResponse;
import com.livingdocs.identity.model.SuccessCode;
import com.livingdocs.identity.service.RepositoryGrantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenants/{tenantId}/workspaces/{workspaceId}/repository-grants")
public class RepositoryGrantController {
    private final RepositoryGrantService service;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "liv162_grant_request", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<SuccessResponse<RepositoryGrantResponse>> request(@AuthenticationPrincipal Jwt user,
            @PathVariable UUID tenantId, @PathVariable UUID workspaceId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey, //Mã nhận diện lần yêu cầu để chống tạo trùng khi retry
            @Valid @RequestBody RepositoryGrantRequest body) {
        var data = service.request(UUID.fromString(user.getSubject()), tenantId, workspaceId,
                body.getWorkspaceMembershipId(), body.getRepositoryConnectionId(),
                body.getProvenance(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(SuccessCode.REPOSITORY_GRANT_REQUESTED, data));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "liv162_grant_list", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<SuccessResponse<List<RepositoryGrantResponse>>> list(@AuthenticationPrincipal Jwt user,
            @PathVariable UUID tenantId, @PathVariable UUID workspaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SuccessResponse.of(
                SuccessCode.REPOSITORY_GRANTS_LOADED,
                service.list(UUID.fromString(user.getSubject()), tenantId, workspaceId)));
    }

    @GetMapping(value = "/{grantId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "liv162_grant_get", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<SuccessResponse<RepositoryGrantResponse>> get(@AuthenticationPrincipal Jwt user,
            @PathVariable UUID tenantId, @PathVariable UUID workspaceId, @PathVariable UUID grantId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SuccessResponse.of(
                SuccessCode.REPOSITORY_GRANT_LOADED,
                service.get(UUID.fromString(user.getSubject()), tenantId, workspaceId, grantId)));
    }

    @PostMapping(value = "/{grantId}/verify", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "liv162_grant_verify", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<SuccessResponse<RepositoryGrantResponse>> verify(@AuthenticationPrincipal Jwt user,
            @PathVariable UUID tenantId, @PathVariable UUID workspaceId, @PathVariable UUID grantId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SuccessResponse.of(
                SuccessCode.REPOSITORY_GRANT_VERIFIED,
                service.verify(UUID.fromString(user.getSubject()), tenantId, workspaceId, grantId)));
    }

    @PostMapping(value = "/{grantId}/revoke", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "liv162_grant_revoke", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<SuccessResponse<RepositoryGrantResponse>> revoke(@AuthenticationPrincipal Jwt user,
            @PathVariable UUID tenantId, @PathVariable UUID workspaceId, @PathVariable UUID grantId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SuccessResponse.of(
                SuccessCode.REPOSITORY_GRANT_REVOKED,
                service.revoke(UUID.fromString(user.getSubject()), tenantId, workspaceId, grantId)));
    }
}
