package com.livingdocs.controller;

import com.livingdocs.dto.request.RepositoryBindingRequest;
import com.livingdocs.dto.response.*;
import com.livingdocs.exception.*;
import com.livingdocs.mapper.RepositoryBindingMapper;
import com.livingdocs.model.SuccessCode;
import com.livingdocs.security.SourceServiceAuthentication;
import com.livingdocs.service.RepositoryBindingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class RepositoryBindingController {
    private final RepositoryBindingService bindings;
    private final RepositoryBindingMapper mapper;

    @PostMapping(value="/internal/v1/repository-bindings/verify", consumes=MediaType.APPLICATION_JSON_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="repositoryBindingVerify", summary="Read fresh authoritative S02 connection binding for S01 grants")
    @SecurityRequirement(name="serviceAuth")
    public SuccessResponse<RepositoryBindingView> verify(@Valid @RequestBody RepositoryBindingRequest request, SourceServiceAuthentication identity) {
        var scope = identity.authorize(request.getRepositoryConnectionId());
        if (!scope.tenantId().equals(request.getTenantId()) || !scope.workspaceId().equals(request.getWorkspaceId()))
            throw new SourceApiException(SourceApiError.FORBIDDEN_SCOPE);
        return SuccessResponse.of(SuccessCode.REPOSITORY_BINDING_VERIFIED, mapper.toView(bindings.verify(scope,request.getRepositoryConnectionId())));
    }
}
