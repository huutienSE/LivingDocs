package com.livingdocs.document.mapper;

import com.livingdocs.document.dto.response.ReviewQueueItem;
import com.livingdocs.document.entity.ReviewQueueEntry;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ReviewQueueMapper {
    ReviewQueueItem toResponse(ReviewQueueEntry entry);
}
