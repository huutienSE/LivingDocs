package com.livingdocs.identity.controller;

import com.livingdocs.identity.dto.response.AccessContextResponse;
import com.livingdocs.identity.dto.response.ProblemResponse;
import com.livingdocs.identity.dto.response.SuccessResponse;
import com.livingdocs.identity.model.SuccessCode;
import com.livingdocs.identity.service.AccessContextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class AccessContextController {
    private final AccessContextService service;

    @GetMapping(value = "/access-context", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "api038_get", summary = "Current scoped access context",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current effective scope snapshot",
                    useReturnTypeSchema = true,
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "ACCESS_CONTEXT_LOADED",
                              "message": "Access context loaded",
                              "data": {
                                "identityId": "5c94a297-169d-4fea-8582-a22f82dd799e",
                                "tenantId": "22222222-2222-4222-8222-222222222222",
                                "authzRevision": 8,
                                "evaluatedAt": "2026-11-16T10:00:00Z",
                                "tenantRoles": [],
                                "tenantPermissions": [],
                                "workspaceGrants": [{
                                  "workspaceId": "33333333-3333-4333-8333-333333333333",
                                  "roles": ["MANAGER", "STAFF"],
                                  "permissions": ["DOCUMENT_FINAL_APPROVE", "DOCUMENT_PUBLISH",
                                    "DOCUMENT_READ", "DOCUMENT_STAFF_REVIEW", "WORKSPACE_VIEW"],
                                  "repositoryIds": []
                                }]
                              }
                            }
                            """))),
            @ApiResponse(responseCode = "401", description = "JWT missing or invalid",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class))),
            @ApiResponse(responseCode = "403", description = "Identity or tenant inactive",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal error",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class)))
    })
    //Spring Security cung cấp JWT đã xác thực. // @AuthenticationPrincipal yêu cầu Spring lấy principal từ SecurityContext
    public ResponseEntity<SuccessResponse<AccessContextResponse>> current(@AuthenticationPrincipal Jwt jwt) {
        AccessContextResponse data = service.current(UUID.fromString(jwt.getSubject())); //Chuyển JWT Subject thành Identity ID và truyen goi den serice
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()) // CacheControl.noStore() → thêm Cache-Control: no-store, tránh lưu response chứa quyền trong HTTP cache.
                .body(SuccessResponse.of(SuccessCode.ACCESS_CONTEXT_LOADED, data));
    }
}
