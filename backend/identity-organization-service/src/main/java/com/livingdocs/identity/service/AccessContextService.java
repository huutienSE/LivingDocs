package com.livingdocs.identity.service;

import com.livingdocs.identity.dto.response.AccessContextResponse;
import java.util.UUID;

public interface AccessContextService {
    AccessContextResponse current(UUID verifiedIdentityId);
}
