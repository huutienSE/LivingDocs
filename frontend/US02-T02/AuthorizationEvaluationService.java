package com.livingdocs.identity.authz;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * API-039 use case: fresh authorization for sensitive operations. Fail-closed:
 * any problem proving the permission ends in an exception (=> 503), never in {@code allowed=true}.
 */
@Component
public class AuthorizationEvaluationService {

    /** How long the caller may still rely on an "allowed" decision. Short on purpose: there is no cross-service atomicity. */
    static final Duration DECISION_TTL = Duration.ofSeconds(30);

    private final AuthorizationFactsRepository repository;
    private Clock clock = Clock.systemUTC();

    public AuthorizationEvaluationService(AuthorizationFactsRepository repository) {
        this.repository = repository;
    }

    /** Test seam (kept off the constructor so Spring has exactly one constructor to autowire). */
    void useClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param authenticatedService identity proven by the transport (mTLS/service token), NOT taken from the body
     */
    public AuthorizationEvaluationResult evaluate(AuthorizationEvaluationRequest request, String authenticatedService) {
        validate(request);
        if (authenticatedService == null || !authenticatedService.equals(request.callerService())) {
            throw new UntrustedCallerException("callerService does not match the authenticated service identity");
        }

        Instant now = clock.instant();
        UUID decisionId = UUID.randomUUID();

        var action = AuthorizationAction.parse(request.requestedAction());
        if (action.isEmpty()) {
            return AuthorizationEvaluationResult.deny(now, decisionId, ReasonCode.UNSUPPORTED_ACTION);
        }
        if (!action.get().allowedCaller().equals(authenticatedService)) {
            throw new UntrustedCallerException("service is not allowed to evaluate " + action.get());
        }

        ActorFacts facts = repository.load(request.actorIdentityId(), request.resourceScope());
        AuthorizationPolicy.Decision d = AuthorizationPolicy.decide(action.get(), facts);
        if (!d.allowed()) {
            return AuthorizationEvaluationResult.deny(now, decisionId, d.reason());
        }
        return AuthorizationEvaluationResult.allow(now, decisionId, d.effectiveRoles(), facts.membershipRevision(), now.plus(DECISION_TTL));
    }

    private static void validate(AuthorizationEvaluationRequest r) {
        if (r == null) {
            throw new InvalidEvaluationRequestException("request body is required");
        }
        if (r.actorIdentityId() == null) {
            throw new InvalidEvaluationRequestException("actorIdentityId is required");
        }
        if (r.requestedAction() == null || r.requestedAction().isBlank()) {
            throw new InvalidEvaluationRequestException("requestedAction is required");
        }
        if (r.resourceScope() == null || r.resourceScope().tenantId() == null || r.resourceScope().workspaceId() == null) {
            throw new InvalidEvaluationRequestException("resourceScope.tenantId and resourceScope.workspaceId are required");
        }
        if (r.callerService() == null || r.callerService().isBlank()) {
            throw new InvalidEvaluationRequestException("callerService is required");
        }
        if (r.correlationId() == null || r.correlationId().isBlank()) {
            throw new InvalidEvaluationRequestException("correlationId is required");
        }
    }
}
