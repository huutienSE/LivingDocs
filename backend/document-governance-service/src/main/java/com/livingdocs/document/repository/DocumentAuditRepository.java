package com.livingdocs.document.repository;

import lombok.RequiredArgsConstructor;
import com.livingdocs.document.model.DocumentAudit;
import java.util.LinkedHashMap;
import com.livingdocs.document.mapper.ReviewPersistenceMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class DocumentAuditRepository {
    private final DocumentAuditJpaRepository audits;
    private final ReviewPersistenceMapper mapper;
    private final JsonMapper json;

    public void insert(DocumentAudit a) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("decisionId", a.decision().id());
        metadata.put("previousReviewStage", a.before().reviewStage());
        metadata.put("newReviewStage", a.after().reviewStage());
        metadata.put("previousRevision", a.before().revision());
        metadata.put("newRevision", a.after().revision());
        metadata.put("evidenceRefs", a.decision().evidenceRefs());
        metadata.put("correlationId", a.correlationId());
        if (a.sourceValidation() != null) metadata.put("sourceValidation", a.sourceValidation());
        audits.saveAndFlush(mapper.toEntity(a, json.writeValueAsString(metadata)));
    }
}
