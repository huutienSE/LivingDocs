package com.livingdocs.service;

import com.livingdocs.model.RepositoryBindingEvidence;
import com.livingdocs.model.SourceScope;
import java.util.UUID;

public interface RepositoryBindingService {
    RepositoryBindingEvidence verify(SourceScope scope, UUID connectionId);
}
