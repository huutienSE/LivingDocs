package com.livingdocs.mapper;

import com.livingdocs.dto.request.*;
import com.livingdocs.dto.response.*;
import com.livingdocs.model.SourceResolveRequest;
import com.livingdocs.model.SourceResolveResult;
import com.livingdocs.model.SourceValidationRequest;
import com.livingdocs.model.SourceValidationResult;
import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SourceHttpMapper {
    default SourceResolveRequest toDomain(SourceResolveHttpRequest request) {
        return new SourceResolveRequest(UUID.fromString(request.getRepositoryConnectionId()), request.getTargetKind().toDomain(),
                request.getTargetRef(), request.getExplicitSha());
    }

    default SourceValidationRequest toDomain(SourceValidateHttpRequest request) {
        return new SourceValidationRequest(UUID.fromString(request.getRepositoryConnectionId()), request.getTargetKind().toDomain(),
                request.getTargetRef(), request.getBoundHeadSha(), request.getConfigRevision());
    }

    @Mapping(target = "tenantId", expression = "java(result.scope().tenantId())")
    @Mapping(target = "workspaceId", expression = "java(result.scope().workspaceId())")
    @Mapping(target = "repositoryConnectionId", expression = "java(result.repositoryConnectionId())")
    @Mapping(target = "targetKind", expression = "java(result.targetKind() == com.livingdocs.model.TargetKind.BRANCH ? com.livingdocs.dto.request.SourceTargetKind.TRACKED_BRANCH : com.livingdocs.dto.request.SourceTargetKind.PR)")
    @Mapping(target = "targetRef", expression = "java(result.targetRef())")
    @Mapping(target = "resolvedSha", expression = "java(result.resolvedSha())")
    @Mapping(target = "configRevision", expression = "java(result.configRevision())")
    SourceSnapshotView toSnapshot(SourceResolveResult result);

    @Mapping(target = "validationStatus", constant = "PASS")
    @Mapping(target = "reason", constant = "CURRENT")
    @Mapping(target = "validationId", expression = "java(result.validationId())")
    @Mapping(target = "tenantId", expression = "java(result.tenantId())")
    @Mapping(target = "workspaceId", expression = "java(result.workspaceId())")
    @Mapping(target = "repositoryConnectionId", expression = "java(result.repositoryConnectionId())")
    @Mapping(target = "targetKind", expression = "java(result.targetKind() == com.livingdocs.model.TargetKind.BRANCH ? com.livingdocs.dto.request.SourceTargetKind.TRACKED_BRANCH : com.livingdocs.dto.request.SourceTargetKind.PR)")
    @Mapping(target = "targetRef", expression = "java(result.targetRef())")
    @Mapping(target = "validatedHeadSha", expression = "java(result.validatedHeadSha())")
    @Mapping(target = "configRevision", expression = "java(result.configRevision())")
    @Mapping(target = "sourceRevision", expression = "java(result.sourceRevision())")
    @Mapping(target = "checkedAt", expression = "java(result.checkedAt())")
    @Mapping(target = "expiresAt", expression = "java(result.expiresAt())")
    SourceValidationReceipt toReceipt(SourceValidationResult result);
}
