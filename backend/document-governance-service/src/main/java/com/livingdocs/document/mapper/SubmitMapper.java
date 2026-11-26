package com.livingdocs.document.mapper;
import com.livingdocs.document.dto.response.ReviewResponse;
import com.livingdocs.document.model.SubmitResult;
import org.mapstruct.*;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SubmitMapper {
    @Mapping(target = "documentId", source = "version.documentId") @Mapping(target = "versionId", source = "version.id")
    @Mapping(target = "state", source = "version.state") @Mapping(target = "reviewStage", source = "version.reviewStage")
    @Mapping(target = "revision", source = "version.revision") @Mapping(target = "sourceBinding", expression = "java(binding(result))")
    ReviewResponse toResponse(SubmitResult result);
    default ReviewResponse.SourceBinding binding(SubmitResult result) {
        var v = result.version(); var b = result.binding();
        return v.repositoryConnectionId() == null ? null : new ReviewResponse.SourceBinding(v.repositoryConnectionId(), b.targetKind(), b.targetRef(), v.sourceSha(), b.comparisonSha(), b.configRevision(), b.capturedAt());
    }
}
