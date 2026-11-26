package com.livingdocs.analysis.port;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Internal port isolating S03 from S01 (API-038 / API-039) and LIV-125 worker lease/scope verification.
 */
public interface ScopeAuthorizationPort {

	AuthorizationDecision verifyUserScope(UserScopeRequest request);

	AuthorizationDecision verifyWorkerScope(WorkerScopeRequest request);

	enum DecisionStatus {
		AUTHORIZED,
		DENIED,
		UNAVAILABLE
	}

	record UserScopeRequest(
			UUID actorIdentityId,
			UUID tenantId,
			UUID workspaceId,
			UUID repositoryConnectionId,
			String action) {

		public UserScopeRequest {
			Objects.requireNonNull(actorIdentityId, "actorIdentityId");
			Objects.requireNonNull(tenantId, "tenantId");
			Objects.requireNonNull(workspaceId, "workspaceId");
			Objects.requireNonNull(action, "action");
		}
	}

	record WorkerScopeRequest(
			String servicePrincipal,
			UUID tenantId,
			UUID workspaceId,
			UUID repositoryConnectionId,
			String externalJobKey,
			String attemptId,
			Set<String> requiredCapabilities) {

		public WorkerScopeRequest {
			Objects.requireNonNull(servicePrincipal, "servicePrincipal");
			Objects.requireNonNull(tenantId, "tenantId");
			Objects.requireNonNull(workspaceId, "workspaceId");
			Objects.requireNonNull(repositoryConnectionId, "repositoryConnectionId");
			Objects.requireNonNull(externalJobKey, "externalJobKey");
			Objects.requireNonNull(attemptId, "attemptId");
			requiredCapabilities = Set.copyOf(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
		}
	}

	record AuthorizationDecision(
			DecisionStatus status,
			Optional<String> denyReasonCode,
			Optional<Long> authzRevision) {

		public AuthorizationDecision {
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(denyReasonCode, "denyReasonCode");
			Objects.requireNonNull(authzRevision, "authzRevision");
		}

		public boolean isAuthorized() {
			return status == DecisionStatus.AUTHORIZED;
		}
	}
}
