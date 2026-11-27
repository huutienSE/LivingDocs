package com.livingdocs.document.mapper;

import com.livingdocs.document.dto.response.*;
import com.livingdocs.document.entity.*;
import com.livingdocs.document.model.DocumentVersion;
import java.util.Map;
import org.mapstruct.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ReviewContextMapper {
    @Mapping(target = "versionId", source = "version.id")
    @Mapping(target = "documentId", source = "version.documentId")
    @Mapping(target = "state", source = "version.state")
    @Mapping(target = "reviewStage", source = "version.reviewStage")
    @Mapping(target = "revision", source = "version.revision")
    @Mapping(target = "sourceBinding", expression = "java(binding(version, row))")
    ReviewResponse version(DocumentVersion version, DocumentVersionEntity row);

    @Mapping(target = "ref", source = "id")
    ReviewContextView.Evidence evidence(DocumentEvidenceEntity row);

    @Mapping(target = "templateId", source = "template.id")
    @Mapping(target = "templateVersionId", source = "version.id")
    @Mapping(target = "name", source = "template.name")
    @Mapping(target = "documentType", source = "template.documentType")
    @Mapping(target = "versionNo", source = "version.versionNo")
    @Mapping(target = "structureRef", source = "version.structureRef")
    @Mapping(target = "publishedAt", source = "version.publishedAt")
    @Mapping(target = "formattingRules", expression = "java(rules(version.getFormattingRulesJson(), json))")
    ReviewContextView.Template template(DocumentTemplateEntity template, DocumentTemplateVersionEntity version, @Context JsonMapper json);

    @Mapping(target = "versionId", source = "version.id")
    @Mapping(target = "versionNo", source = "version.versionNo")
    @Mapping(target = "state", source = "version.state")
    @Mapping(target = "sourceSha", source = "version.sourceSha")
    @Mapping(target = "content", source = "content.content")
    @Mapping(target = "contentFormat", source = "content.contentFormat")
    @Mapping(target = "contentRef", source = "content.contentRef")
    @Mapping(target = "contentRevisionNo", source = "content.revisionNo")
    ReviewContextView.Baseline baseline(DocumentVersionEntity version, DocumentContentRevisionEntity content);

    default Map<String, Object> rules(String value, JsonMapper json) {
        return json.readValue(value, new TypeReference<Map<String, Object>>() {});
    }

    default ReviewResponse.SourceBinding binding(DocumentVersion version, DocumentVersionEntity row) {
        return version.repositoryConnectionId() == null ? null : new ReviewResponse.SourceBinding(
                version.repositoryConnectionId(), row.getSourceTargetKind(), row.getSourceTargetRef(), row.getSourceSha(),
                row.getSourceComparisonSha(), row.getSourceConfigRevision(), row.getSourceCapturedAt());
    }
}
