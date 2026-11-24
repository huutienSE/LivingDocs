package com.livingdocs.identity.service;

import com.livingdocs.identity.dto.request.AuthorizationEvaluationRequest;
import com.livingdocs.identity.dto.response.AuthorizationEvaluationResponse;
import java.util.UUID;

public interface AuthorizationEvaluationService {
    AuthorizationEvaluationResponse evaluate(UUID actorId, AuthorizationEvaluationRequest request);
}
