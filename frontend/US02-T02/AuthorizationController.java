package com.livingdocs.identity.authz;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * API-039 — POST /internal/v1/authorization/evaluate (S01, internal only).
 * Must never be routed by the public API Gateway. A denial is a DECISION (HTTP 200, allowed=false); it is the caller (S04)
 * that turns it into 403 FORBIDDEN_SCOPE / PERMISSION_REVOKED for the end user.
 */
@RestController
public class AuthorizationController {

    private final AuthorizationEvaluationService service;
    private final ServiceIdentityVerifier verifier;

    public AuthorizationController(AuthorizationEvaluationService service, ServiceIdentityVerifier verifier) {
        this.service = service;
        this.verifier = verifier;
    }

    @PostMapping("/internal/v1/authorization/evaluate")
    public AuthorizationEvaluationResult evaluate(@RequestBody AuthorizationEvaluationRequest request, HttpServletRequest http) {
        String service = verifier.authenticate(http).orElseThrow(() -> new UntrustedCallerException("caller is not an authenticated internal service"));
        return this.service.evaluate(request, service);
    }
}
