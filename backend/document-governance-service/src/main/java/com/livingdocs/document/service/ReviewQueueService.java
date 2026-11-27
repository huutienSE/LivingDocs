package com.livingdocs.document.service;

import com.livingdocs.document.dto.request.ReviewQueueQuery;
import com.livingdocs.document.dto.response.ReviewQueueView;
import com.livingdocs.document.model.S01AccessContext;

public interface ReviewQueueService {
    ReviewQueueView list(S01AccessContext actor, ReviewQueueQuery query);
}
