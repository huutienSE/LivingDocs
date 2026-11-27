package com.livingdocs.document.mapper;

import com.livingdocs.document.dto.response.ApprovalContextResponse;
import com.livingdocs.document.dto.response.ApprovalQueueResponse;
import com.livingdocs.document.dto.response.ReviewResponse;
import com.livingdocs.document.model.ApprovalContext;
import com.livingdocs.document.model.ApprovalQueueItem;
import com.livingdocs.document.model.ApprovalQueuePage;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** Pure model → DTO mapping; no database access or authorization here. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ApprovalQueryMapper {
    ApprovalQueueResponse toResponse(ApprovalQueuePage page);

    ApprovalQueueResponse.Item toResponse(ApprovalQueueItem item);

    ApprovalContextResponse toResponse(ApprovalContext context);

    ReviewResponse toResponse(ApprovalContext.Version version);

    ReviewResponse.SourceBinding toResponse(ApprovalContext.SourceBinding binding);

    ApprovalContextResponse.Content toResponse(ApprovalContext.Content content);

    ApprovalContextResponse.StaffDecision toResponse(ApprovalContext.StaffDecision decision);

    ApprovalContextResponse.ManagerDecision toResponse(ApprovalContext.ManagerDecision decision);

    ApprovalContextResponse.Evidence toResponse(ApprovalContext.Evidence evidence);
}
