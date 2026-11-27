package com.livingdocs.mapper;

import com.livingdocs.model.RepositoryBindingEvidence;
import com.livingdocs.dto.response.RepositoryBindingView;
import org.mapstruct.*;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RepositoryBindingMapper {
    @Mapping(target="tenantId", expression="java(source.getTenantId())")
    @Mapping(target="workspaceId", expression="java(source.getWorkspaceId())")
    @Mapping(target="repositoryConnectionId", expression="java(source.getRepositoryConnectionId())")
    @Mapping(target="status", expression="java(source.getStatus())")
    @Mapping(target="bindingRevision", expression="java(source.getBindingRevision())")
    @Mapping(target="observedAt", expression="java(source.getObservedAt())")
    RepositoryBindingView toView(RepositoryBindingEvidence source);
}
