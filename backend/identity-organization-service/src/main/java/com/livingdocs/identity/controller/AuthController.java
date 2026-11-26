package com.livingdocs.identity.controller;

import com.livingdocs.identity.dto.request.RegisterRequest;
import com.livingdocs.identity.dto.request.LoginRequest;
import com.livingdocs.identity.dto.response.LoginResponse;
import com.livingdocs.identity.dto.response.ProblemResponse;
import com.livingdocs.identity.dto.response.RegisterResponse;
import com.livingdocs.identity.dto.response.SuccessResponse;
import com.livingdocs.identity.model.SuccessCode;
import com.livingdocs.identity.service.RegistrationService;
import com.livingdocs.identity.service.LoginService;
import com.livingdocs.identity.security.JwtTokenIssuer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RegistrationService registrationService;
    private final LoginService loginService;
    private final JwtTokenIssuer tokens;

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "api002_post", summary = "Log in with local credential and issue User JWT")
    // khai báo api documentation với swagger
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Access token issued", useReturnTypeSchema = true,
                    headers = @Header(name = "Cache-Control", schema = @Schema(allowableValues = "no-store"))),
            // bad req
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class))),
            // unauthorized
            @ApiResponse(responseCode = "401", description = "Invalid credentials",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class))),
            @ApiResponse(responseCode = "415", description = "Content-Type must be application/json",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class))),
            // to many requests
            @ApiResponse(responseCode = "429", description = "Login rate limited",
                    headers = {
                            @Header(name = "Cache-Control", schema = @Schema(allowableValues = "no-store")),
                            @Header(name = "Retry-After", description = "Seconds until retry", schema = @Schema(type = "string"))
                    },
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class))),
            // service unavailable
            @ApiResponse(responseCode = "503", description = "Login security unavailable",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal error",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class)))
    })

    // @RequestBody tự động chuyển JSON sang obj LoginRequest
    // @Valid kích hoạt các annnotation được định nghĩa bên trong class LoginReuest
    public ResponseEntity<SuccessResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        UUID identityId = loginService.verifyCredentials(request.getEmail(), request.getPassword());
        String token = tokens.issue(identityId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(SuccessCode.LOGIN_SUCCEEDED, new LoginResponse(token, JwtTokenIssuer.EXPIRES_IN_SECONDS)));
    }

    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "api001_post", summary = "Register a local Identity without tenant or role")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Identity created. data.identityId is the new identity UUID.",
                    useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class))),
            @ApiResponse(responseCode = "409", description = "Identity already exists",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class))),
            @ApiResponse(responseCode = "415", description = "Unsupported media type",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal error",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemResponse.class)))
    })
    public ResponseEntity<SuccessResponse<RegisterResponse>> register(@Valid @RequestBody RegisterRequest request) {
        UUID identityId = registrationService.registerLocal(request.getEmail(), request.getPassword());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of(SuccessCode.IDENTITY_REGISTERED, new RegisterResponse(identityId)));
    }
}
