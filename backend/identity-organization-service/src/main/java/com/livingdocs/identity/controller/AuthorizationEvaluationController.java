package com.livingdocs.identity.controller;

import com.livingdocs.identity.dto.request.AuthorizationEvaluationRequest;
import com.livingdocs.identity.dto.response.AuthorizationEvaluationResponse;
import com.livingdocs.identity.dto.response.ProblemResponse;
import com.livingdocs.identity.dto.response.SuccessResponse;
import com.livingdocs.identity.model.SuccessCode;
import com.livingdocs.identity.security.ServiceAssertionVerifier;
import com.livingdocs.identity.service.AuthorizationEvaluationService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/*
 * Spring Security xác minh User JWT  trước khi vào Controller. S01SecurityConfig
 *LUỒNG XỬ LÝ:
 *  * 1. Spring Security xác minh User JWT trước khi vào Controller.
 *  * 2. Controller nhận User JWT, Service Assertion và JSON body.
 *  * 3. Kiểm tra yêu cầu sử dụng transport được phép.
 *  * 4. Gọi ServiceAssertionVerifier để xác minh Service JWT,
 *  *    sự ràng buộc với User JWT và action/resource scope.
 *  * 5. Gọi AuthorizationEvaluationService để kiểm quyền hiện hành
 *  *    của An từ dữ liệu authority S01.
 *  * 6. Trả kết quả ALLOW hoặc DENY cho S02.
 *  *
 *  * PHÂN BIỆT:
 *  * - ServiceAssertionVerifier: S02 có đáng tin và được phép
 *  *   gửi yêu cầu kiểm quyền này không?
 *  * - AuthorizationEvaluationService: An có thực sự còn quyền
 *  *   REPOSITORY_CONNECT trong Workspace không?
 */
@RestController
@RequiredArgsConstructor
public class AuthorizationEvaluationController {
    private final AuthorizationEvaluationService service; // KIỂM QUYỀN THỰC TẾ CỦA CLIENT TRONG TENANT/WORKSPACE/REPO
    private final ServiceAssertionVerifier assertions; // XÁC MINH SERVICE JWT VÀ RÀNG BUỘC VỚI USER JWT
    @Value("${livingdocs.security.workloads.allow-insecure-transport:false}")
    private boolean allowInsecureTransport;

    @PostMapping(value = "/internal/v1/authorization/evaluate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "api039_post", summary = "Evaluate fresh human authority in one scoped snapshot",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = AuthorizationEvaluationRequest.class),
                            examples = {
                                    @ExampleObject(name = "repositoryConnect", summary = "S02 checks Workspace authority before connection creation; omit repositoryConnectionId",
                                            value = """
                                                    {"action":"REPOSITORY_CONNECT","tenantId":"11111111-1111-4111-8111-111111111111","workspaceId":"22222222-2222-4222-8222-222222222222"}
                                                    """),
                                    @ExampleObject(name = "documentPublish", summary = "S04 checks a Document action with repository scope",
                                            value = """
                                                    {"action":"DOCUMENT_PUBLISH","tenantId":"11111111-1111-4111-8111-111111111111","workspaceId":"22222222-2222-4222-8222-222222222222","repositoryConnectionId":"33333333-3333-4333-8333-333333333333"}
                                                    """)
                            })))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "ALLOW or DENY", useReturnTypeSchema = true,
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "allow", summary = "Illustrative ALLOW; IDs, revision and time vary per request",
                                    value = """
                                            {"code":"AUTHORIZATION_EVALUATED","message":"Authorization evaluated","data":{"decision":"ALLOW","reasonCode":null,"action":"REPOSITORY_CONNECT","tenantId":"11111111-1111-4111-8111-111111111111","workspaceId":"22222222-2222-4222-8222-222222222222","repositoryConnectionId":null,"authzRevision":8,"evaluatedAt":"2026-11-24T10:00:00Z","decisionId":"44444444-4444-4444-8444-444444444444"}}
                                            """),
                            @ExampleObject(name = "deny", summary = "DENY is an HTTP 200 authority decision",
                                    value = """
                                            {"code":"AUTHORIZATION_EVALUATED","message":"Authorization evaluated","data":{"decision":"DENY","reasonCode":"NOT_AUTHORIZED","action":"REPOSITORY_CONNECT","tenantId":"11111111-1111-4111-8111-111111111111","workspaceId":"22222222-2222-4222-8222-222222222222","repositoryConnectionId":null,"authzRevision":8,"evaluatedAt":"2026-11-24T10:00:00Z","decisionId":"44444444-4444-4444-8444-444444444444"}}
                                            """)
                    })),
            @ApiResponse(responseCode = "400", description = "Invalid action or scope", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(value = """
                            {"type":"about:blank","title":"Invalid request","status":400,"detail":"Internal request is invalid","instance":"urn:uuid:44444444-4444-4444-8444-444444444444","code":"INVALID_REQUEST","correlationId":"example-correlation-id"}
                            """))),
            @ApiResponse(responseCode = "401", description = "Invalid human or service JWT", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(summary = "Example for an invalid Service JWT", value = """
                            {"type":"about:blank","title":"Unauthenticated","status":401,"detail":"Invalid service assertion","instance":"urn:uuid:44444444-4444-4444-8444-444444444444","code":"INVALID_SERVICE_ASSERTION","correlationId":"example-correlation-id"}
                            """))),
            @ApiResponse(responseCode = "403", description = "Transport denied", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(value = """
                            {"type":"about:blank","title":"Forbidden","status":403,"detail":"Workload scope is not authorized","instance":"urn:uuid:44444444-4444-4444-8444-444444444444","code":"WORKLOAD_SCOPE_DENIED","correlationId":"example-correlation-id"}
                            """))),
            @ApiResponse(responseCode = "503", description = "Authority unavailable", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(value = """
                            {"type":"about:blank","title":"Service unavailable","status":503,"detail":"Authority is temporarily unavailable","instance":"urn:uuid:44444444-4444-4444-8444-444444444444","code":"AUTHORITY_UNAVAILABLE","correlationId":"example-correlation-id"}
                            """)))
    })
    public ResponseEntity<SuccessResponse<AuthorizationEvaluationResponse>> evaluate(
            @AuthenticationPrincipal Jwt user, // đã xác minh user jwt
            @RequestHeader(value = "Authorization", required = false) String userAuthorization,
            @RequestHeader(value = "X-LD-Service-Assertion", required = false) String serviceAssertion, // service jwt dã xin ở issuer
            @Valid @RequestBody AuthorizationEvaluationRequest body,
            HttpServletRequest http) {

        //yêu cầu https/tls
        if (!http.isSecure() && !allowInsecureTransport) throw new com.livingdocs.identity.service.impl.ServiceTokenServiceImpl.WorkloadScopeException();

        // xác minh service assertion -(service jwt), kiểm sẻvice gọi có là workload đáng tin, service jwt hợp lệ, gắn đúng user jwt và đúng body
        assertions.verifyForEvaluation(serviceAssertion, userAuthorization, body);

        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(SuccessCode.AUTHORIZATION_EVALUATED,
                        service.evaluate(UUID.fromString(user.getSubject()), body)));
    }
}
