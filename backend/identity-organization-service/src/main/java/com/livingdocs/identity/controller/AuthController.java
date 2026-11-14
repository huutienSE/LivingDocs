package com.livingdocs.identity.controller;

import com.livingdocs.identity.dto.request.RegisterRequest;
import com.livingdocs.identity.dto.response.ProblemResponse;
import com.livingdocs.identity.dto.response.RegisterResponse;
import com.livingdocs.identity.dto.response.SuccessResponse;
import com.livingdocs.identity.model.SuccessCode;
import com.livingdocs.identity.service.RegistrationService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
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
