package com.livingdocs.document.service.impl;
import com.livingdocs.document.entity.*;
import com.livingdocs.document.exception.*;
import com.livingdocs.document.model.*;
import com.livingdocs.document.repository.*;
import com.livingdocs.document.service.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
@Service 
@RequiredArgsConstructor
public class DocumentSubmitServiceImpl implements DocumentSubmitService {
    private final DocumentVersionRepository versions;
    private final DocumentSubmitRepository drafts;
    private final DocumentSubmissionJpaRepository submissions;
    private final DocumentAuditJpaRepository audits;
    private final SubmitSnapshotCodec snapshots;
    private final JsonMapper json;
    @Override @Transactional
    public SubmitResult submit(UUID id, SubmitCommand command, long revision, String key, SubmitActorContext actor, String correlation) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}") || revision < 0 || command == null || command.note() != null && command.note().length() > 2000) throw new SubmitException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "Invalid submit request.");
        versions.setTenant(actor.tenantId());
        var before = versions.find(id, actor.tenantId(), true).orElseThrow(DocumentVersionNotFoundException::new); actor.requireAuthor(before);
        var old = submissions.findByTenantIdAndVersionIdAndActorIdAndKey(actor.tenantId(), id, actor.identityId(), key);
        if (old.isPresent()) { var p = old.get(); if (p.getExpectedRevision() != revision || !Objects.equals(p.getNote(), command.note())) throw new ReviewIdempotencyConflictException(); return snapshots.decode(p.getResponseSnapshot()); }
        if (before.revision() != revision) throw new ReviewRevisionConflictException();
        if (before.state() != DocumentState.DRAFT || before.reviewStage() != null) throw new SubmitException("STATE_TRANSITION_FORBIDDEN", HttpStatus.CONFLICT, "Only Draft can be submitted.");
        var evidence = drafts.evidence(actor.tenantId(), id);
        if (evidence.isEmpty()) throw new SubmitException("SUBMIT_EVIDENCE_REQUIRED", HttpStatus.UNPROCESSABLE_ENTITY, "Draft evidence is required.");
        var binding = before.repositoryConnectionId() == null ? null : drafts.binding(actor.tenantId(), id);
        if (binding != null && (!binding.complete() || before.sourceSha() == null || !before.sourceSha().matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"))) throw new SourceEvidenceInsufficientException();
        var after = new DocumentVersion(id, before.tenantId(), before.documentId(), before.workspaceId(), before.repositoryConnectionId(), before.sourceSha(), DocumentState.IN_REVIEW, ReviewStage.STAFF_REVIEW, revision + 1);
        var result = SubmitResult.recorded(after, binding); var snapshot = snapshots.encode(result); var now = Instant.now(); var submissionId = UUID.randomUUID();
        drafts.submit(before);
        submissions.saveAndFlush(DocumentSubmissionEntity.builder().id(submissionId).tenantId(actor.tenantId()).versionId(id).actorId(actor.identityId()).key(key).expectedRevision(revision).resultingRevision(revision+1).note(command.note()).responseSnapshot(snapshot).submittedAt(now).build());
        var metadata = new LinkedHashMap<String,Object>(); metadata.put("submissionId", submissionId); metadata.put("previousRevision", revision); metadata.put("newRevision", revision+1); metadata.put("previousReviewStage", before.reviewStage()); metadata.put("newReviewStage", ReviewStage.STAFF_REVIEW); metadata.put("evidenceRefs", evidence); metadata.put("sourceBinding", binding); metadata.put("note", command.note()); metadata.put("correlationId", correlation);
        audits.saveAndFlush(DocumentAuditEntity.builder().id(UUID.randomUUID()).tenantId(actor.tenantId()).documentId(before.documentId()).versionId(id).workspaceId(before.workspaceId()).repositoryConnectionId(before.repositoryConnectionId()).actorRef(actor.identityId().toString()).action("DOCUMENT_SUBMITTED").previousState(before.state()).newState(after.state()).sourceSha(before.sourceSha()).metadataJson(json.writeValueAsString(metadata)).occurredAt(now).build()); return result;
    }
}
