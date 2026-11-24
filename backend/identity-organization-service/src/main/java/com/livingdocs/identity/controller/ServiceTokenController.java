package com.livingdocs.identity.controller;

import com.livingdocs.identity.dto.request.ServiceTokenRequest;
import com.livingdocs.identity.dto.response.ServiceTokenResponse;
import com.livingdocs.identity.dto.response.ProblemResponse;
import com.livingdocs.identity.dto.response.SuccessResponse;
import com.livingdocs.identity.model.SuccessCode;
import com.livingdocs.identity.service.ServiceTokenService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ServiceTokenController {
    private final ServiceTokenService service;

    @Value("${livingdocs.security.workloads.allow-insecure-transport:false}")
    private boolean allowInsecureTransport;

    // nhận JSON và trả JSON
    @PostMapping(value = "/internal/v1/auth/service-tokens", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "issueServiceToken", summary = "Issue short-lived scoped service JWT",
            security = @SecurityRequirement(name = "workloadBasic"),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ServiceTokenRequest.class),
                            examples = {
                                    @ExampleObject(name = "s02Authorization", summary = "S02 asks for an API-039 assertion; actor hash must match the raw User JWT",
                                            value = """
                                                    {"audience":"livingdocs-s01-internal","operation":"authorization:evaluate","method":"POST","path":"/internal/v1/authorization/evaluate","tenantId":"11111111-1111-4111-8111-111111111111","workspaceId":"22222222-2222-4222-8222-222222222222","action":"REPOSITORY_CONNECT","actorTokenSha256":"0000000000000000000000000000000000000000000000000000000000000000"}
                                                    """),
                                    @ExampleObject(name = "s03SourceResolve", summary = "S03 workload-only source resolution",
                                            value = """
                                                    {"audience":"livingdocs-s02-internal","operation":"source:resolve","method":"POST","path":"/internal/v1/source/resolve","tenantId":"11111111-1111-4111-8111-111111111111","workspaceId":"22222222-2222-4222-8222-222222222222","repositoryConnectionId":"33333333-3333-4333-8333-333333333333"}
                                                    """),
                                    @ExampleObject(name = "s04SourceValidate", summary = "S04 workload-only source validation",
                                            value = """
                                                    {"audience":"livingdocs-s02-internal","operation":"source:validate","method":"POST","path":"/internal/v1/source/validate","tenantId":"11111111-1111-4111-8111-111111111111","workspaceId":"22222222-2222-4222-8222-222222222222","repositoryConnectionId":"33333333-3333-4333-8333-333333333333"}
                                                    """),
                                    @ExampleObject(name = "s04Document", summary = "S04 asks for an API-039 Document assertion with repository scope",
                                            value = """
                                                    {"audience":"livingdocs-s01-internal","operation":"authorization:evaluate","method":"POST","path":"/internal/v1/authorization/evaluate","tenantId":"11111111-1111-4111-8111-111111111111","workspaceId":"22222222-2222-4222-8222-222222222222","repositoryConnectionId":"33333333-3333-4333-8333-333333333333","action":"DOCUMENT_PUBLISH","actorTokenSha256":"0000000000000000000000000000000000000000000000000000000000000000"}
                                                    """)
                            })))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Scoped JWT issued", useReturnTypeSchema = true,
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(summary = "Illustrative response; accessToken is a placeholder",
                            value = """
                                    {"code":"SERVICE_TOKEN_ISSUED","message":"Service token issued","data":{"accessToken":"<signed-service-jwt>","tokenType":"Bearer","expiresIn":60}}
                                    """))),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(value = """
                            {"type":"about:blank","title":"Invalid request","status":400,"detail":"Internal request is invalid","instance":"urn:uuid:44444444-4444-4444-8444-444444444444","code":"INVALID_REQUEST","correlationId":"example-correlation-id"}
                            """))),
            @ApiResponse(responseCode = "401", description = "Invalid workload credential", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(value = """
                            {"type":"about:blank","title":"Unauthenticated","status":401,"detail":"Invalid workload credential","instance":"urn:uuid:44444444-4444-4444-8444-444444444444","code":"INVALID_WORKLOAD_CREDENTIAL","correlationId":"example-correlation-id"}
                            """))),
            @ApiResponse(responseCode = "403", description = "Workload scope denied", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(value = """
                            {"type":"about:blank","title":"Forbidden","status":403,"detail":"Workload scope is not authorized","instance":"urn:uuid:44444444-4444-4444-8444-444444444444","code":"WORKLOAD_SCOPE_DENIED","correlationId":"example-correlation-id"}
                            """))),
            @ApiResponse(responseCode = "503", description = "Credential registry unavailable", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(value = """
                            {"type":"about:blank","title":"Service unavailable","status":503,"detail":"Authority is temporarily unavailable","instance":"urn:uuid:44444444-4444-4444-8444-444444444444","code":"AUTHORITY_UNAVAILABLE","correlationId":"example-correlation-id"}
                            """)))
    })
    public ResponseEntity<SuccessResponse<ServiceTokenResponse>> issue(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ServiceTokenRequest body, HttpServletRequest http) {
        // bắt buộc phải https và tls. Nếu http không mã hóa và KHÔNG cho phép được truyền không mã hóa --> exception
        if (!http.isSecure() && !allowInsecureTransport) {
            throw new com.livingdocs.identity.service.impl.ServiceTokenServiceImpl.WorkloadScopeException();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(SuccessCode.SERVICE_TOKEN_ISSUED, service.issue(authorization, body)));
    }
}
