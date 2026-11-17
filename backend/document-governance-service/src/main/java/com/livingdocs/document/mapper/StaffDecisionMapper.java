package com.livingdocs.document.mapper;

import com.livingdocs.document.dto.request.ReviewRequest;
import com.livingdocs.document.dto.response.ReviewResponse;
import com.livingdocs.document.model.StaffDecisionCommand;
import com.livingdocs.document.model.StaffReviewResult;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StaffDecisionMapper {
    StaffDecisionCommand toCommand(ReviewRequest request);
    ReviewResponse toResponse(StaffReviewResult result);

    @Mapping(target = "receipt", source = "receipt")
    StaffReviewResult toResult(ReviewResponse response, StaffReviewResult.Receipt receipt);
}
