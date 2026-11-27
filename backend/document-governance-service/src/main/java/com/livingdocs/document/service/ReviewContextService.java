package com.livingdocs.document.service;

import com.livingdocs.document.dto.response.ReviewContextView;
import com.livingdocs.document.model.S01AccessContext;
import java.util.UUID;

public interface ReviewContextService {
    ReviewContextView load(UUID versionId, S01AccessContext actor);
}
