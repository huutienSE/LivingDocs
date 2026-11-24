package com.livingdocs.identity.service;

import com.livingdocs.identity.dto.request.ServiceTokenRequest;
import com.livingdocs.identity.dto.response.ServiceTokenResponse;

public interface ServiceTokenService {
    ServiceTokenResponse issue(String basicAuthorization, ServiceTokenRequest request);
}
