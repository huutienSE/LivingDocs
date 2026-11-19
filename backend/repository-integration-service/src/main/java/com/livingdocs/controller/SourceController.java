package com.livingdocs.controller;

import com.livingdocs.dto.request.*;
import com.livingdocs.dto.response.*;
import com.livingdocs.exception.*;
import com.livingdocs.mapper.SourceHttpMapper;
import com.livingdocs.model.SuccessCode;
import com.livingdocs.model.ValidationReason;
import com.livingdocs.model.ValidationStatus;
import com.livingdocs.security.SourceServiceAuthentication;
import com.livingdocs.service.SourceResolveService;
import com.livingdocs.service.SourceValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/internal/v1/source")
@SecurityRequirement(name = "serviceAuth")
public class SourceController {
    private final SourceResolveService resolver;
    private final SourceValidationService validator;
    private final SourceHttpMapper mapper;

    @PostMapping(value = "/resolve", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "api069_post", summary = "Resolve authoritative source head")
    public ResponseEntity<SuccessResponse<SourceSnapshotView>> resolve(@Valid @RequestBody SourceResolveHttpRequest body,
                                                                     SourceServiceAuthentication identity) {
        checkTarget(body.getTargetKind(), body.getTargetRef());
        var request = mapper.toDomain(body);
        var result = resolver.resolve(identity.authorize(request.repositoryConnectionId()), request);
        requireCurrent(result.reason());
        log.atInfo().addKeyValue("operation", "resolve").addKeyValue("outcome", "CURRENT").log("Source request completed");
        return ResponseEntity.ok(SuccessResponse.of(SuccessCode.SOURCE_RESOLVED, mapper.toSnapshot(result)));
    }

    @PostMapping(value = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "api070_post", summary = "Validate bound source head")
    public ResponseEntity<SuccessResponse<SourceValidationReceipt>> validate(@Valid @RequestBody SourceValidateHttpRequest body,
                                                                          SourceServiceAuthentication identity) {
        checkTarget(body.getTargetKind(), body.getTargetRef());
        var request = mapper.toDomain(body);
        var result = validator.validate(identity.authorize(request.repositoryConnectionId()), request);
        requireCurrent(result.reason());
        if (result.validationStatus() != ValidationStatus.PASS) throw new SourceApiException(SourceApiError.INTERNAL_ERROR);
        if (!Instant.now().isBefore(result.expiresAt())) throw new SourceApiException(SourceApiError.VALIDATION_EXPIRED);
        log.atInfo().addKeyValue("operation", "validate").addKeyValue("outcome", "CURRENT")
                .addKeyValue("validationId", result.validationId()).log("Source request completed");
        return ResponseEntity.ok(SuccessResponse.of(SuccessCode.SOURCE_VALIDATED, mapper.toReceipt(result)));
    }

    private static void checkTarget(SourceTargetKind kind, String ref) {
        if (!kind.toDomain().isValidRef(ref)) throw new SourceApiException(SourceApiError.INVALID_REQUEST);
    }

    private static void requireCurrent(ValidationReason reason) {
        if (reason != ValidationReason.CURRENT) throw new SourceApiException(SourceApiError.from(reason));
    }
}
