package com.livingdocs.document.mapper;

import com.livingdocs.document.entity.*;
import com.livingdocs.document.model.*;
import java.util.List;
import java.util.UUID;
import org.mapstruct.*;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ReviewPersistenceMapper {
    @Mapping(target = "newEntity", ignore = true)
    ReviewDecisionEntity toEntity(ReviewDecision decision, String evidenceRefsJson, String responseSnapshot);

    @Mapping(target = "evidenceRefs", source = "evidenceRefs")
    ReviewDecision toModel(ReviewDecisionEntity entity, List<UUID> evidenceRefs);

    @Mapping(target = "tenantId", source = "audit.before.tenantId")
    @Mapping(target = "documentId", source = "audit.before.documentId")
    @Mapping(target = "versionId", source = "audit.before.id")
    @Mapping(target = "workspaceId", source = "audit.before.workspaceId")
    @Mapping(target = "repositoryConnectionId", source = "audit.before.repositoryConnectionId")
    @Mapping(target = "actorRef", expression = "java(audit.actorId().toString())")
    @Mapping(target = "action", expression = "java(\"STAFF_REVIEW_\" + audit.decision().decision().name())")
    @Mapping(target = "previousState", source = "audit.before.state")
    @Mapping(target = "newState", source = "audit.after.state")
    @Mapping(target = "sourceSha", source = "audit.before.sourceSha")
    @Mapping(target = "newEntity", ignore = true)
    DocumentAuditEntity toEntity(DocumentAudit audit, String metadataJson);
}
