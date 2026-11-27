package com.livingdocs.document.service.impl;

import com.livingdocs.document.config.ReviewContextProperties;
import com.livingdocs.document.dto.response.ReviewContextView;
import com.livingdocs.document.exception.*;
import com.livingdocs.document.mapper.ReviewContextMapper;
import com.livingdocs.document.model.DocumentState;
import com.livingdocs.document.model.S01AccessContext;
import com.livingdocs.document.repository.*;
import com.livingdocs.document.security.StaffReadPolicy;
import com.livingdocs.document.service.ReviewContextService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Service @RequiredArgsConstructor
public class ReviewContextServiceImpl implements ReviewContextService {
    private final DocumentVersionRepository scopes;
    private final DocumentVersionJpaRepository versions;
    private final DocumentJpaRepository documents;
    private final DocumentContentRevisionJpaRepository contents;
    private final DocumentTemplateJpaRepository templates;
    private final DocumentTemplateVersionJpaRepository templateVersions;
    private final DocumentEvidenceJpaRepository evidence;
    private final DocumentSubmissionJpaRepository submissions;
    private final ReviewDecisionJpaRepository decisions;
    private final ReviewContextMapper mapper;
    private final ReviewContextProperties properties;
    private final JsonMapper json;

    // All reads, including baseline and ETag revision, observe the same canonical snapshot.
    @Override @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReviewContextView load(UUID versionId, S01AccessContext actor) {
        var grants = StaffReadPolicy.grants(actor);
        if (grants.isEmpty()) throw new InsufficientReviewScopeException();
        scopes.setTenant(actor.tenantId());
        scopes.setQueryTimeout(properties.getQueryTimeoutMs());
        var version = scopes.find(versionId, actor.tenantId(), false).orElseThrow(DocumentVersionNotFoundException::new);
        if (!StaffReadPolicy.permits(grants, version.workspaceId(), version.repositoryConnectionId()))
            throw new DocumentVersionNotFoundException();
        // Do not load any content/template/evidence until the exact resource scope is authorized.
        var row = versions.findById(versionId).orElseThrow(DocumentVersionNotFoundException::new);
        var document = documents.findByTenantIdAndId(actor.tenantId(), version.documentId()).orElseThrow(DocumentVersionNotFoundException::new);
        var content = contents.findFirstByTenantIdAndVersionIdOrderByRevisionNoDesc(actor.tenantId(), versionId).orElse(null);
        ReviewContextView.Template template = null;
        if (row.getTemplateVersionId() != null) {
            var selected = templateVersions.findByTenantIdAndId(actor.tenantId(), row.getTemplateVersionId()).orElseThrow(DocumentVersionNotFoundException::new);
            var definition = templates.findByTenantIdAndId(actor.tenantId(), selected.getTemplateId()).orElseThrow(DocumentVersionNotFoundException::new);
            boolean allowed = "TENANT".equals(definition.getScopeType()) && actor.tenantId().equals(definition.getScopeRef())
                    || "WORKSPACE".equals(definition.getScopeType()) && version.workspaceId().equals(definition.getScopeRef());
            if (!allowed) throw new DocumentVersionNotFoundException();
            template = mapper.template(definition, selected, json);
        }
        var baseline = versions.findFirstByTenantIdAndDocumentIdAndVersionNoLessThanAndStateInOrderByVersionNoDesc(
                actor.tenantId(), version.documentId(), row.getVersionNo(), List.of(DocumentState.APPROVED, DocumentState.PUBLISHED))
                .map(previous -> mapper.baseline(previous,
                        contents.findFirstByTenantIdAndVersionIdOrderByRevisionNoDesc(actor.tenantId(), previous.getId()).orElse(null)))
                .orElse(null);
        var submission = submissions.findFirstByTenantIdAndVersionIdOrderByResultingRevisionDesc(actor.tenantId(), versionId).orElse(null);
        return ReviewContextView.builder().version(mapper.version(version, row))
                .documentTitle(document.getTitle()).versionNo(row.getVersionNo())
                .content(content == null ? null : content.getContent()).contentFormat(content == null ? null : content.getContentFormat())
                .contentRef(content == null ? null : content.getContentRef()).contentRevisionNo(content == null ? null : content.getRevisionNo())
                .template(template).baseline(baseline)
                .submittedAt(submission == null ? null : submission.getSubmittedAt()).submittedBy(submission == null ? null : submission.getActorId())
                .reviewRound(decisions.nextRound(actor.tenantId(), versionId) - 1)
                .evidence(evidence.findByTenantIdAndVersionIdOrderById(actor.tenantId(), versionId).stream().map(mapper::evidence).toList())
                .build();
    }
}
