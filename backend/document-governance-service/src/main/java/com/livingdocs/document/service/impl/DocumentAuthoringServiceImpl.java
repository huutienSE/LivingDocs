package com.livingdocs.document.service.impl;

import com.livingdocs.document.client.*;
import com.livingdocs.document.entity.*;
import com.livingdocs.document.exception.*;
import com.livingdocs.document.model.*;
import com.livingdocs.document.repository.*;
import com.livingdocs.document.service.DocumentAuthoringService;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class DocumentAuthoringServiceImpl implements DocumentAuthoringService {
    private final DocumentAuthoringRepository repository;
    private final DocumentAuditJpaRepository audits;
    private final S01DocumentAuthorizationClient authority;
    private final S01FreshAuthorizationClient fresh;
    private final SourceSnapshotProvider snapshots;
    private final PlatformTransactionManager transactions;
    private final JsonMapper json;

    @Override
    public DocumentDetails createDocument(UUID workspaceId, UUID collectionId, UUID repositoryId, String title,
            S01AccessContext actor, String token, String correlationId) {
        var scope = read(actor, () -> {
            var collection = repository.collection(collectionId, actor.tenantId(), false);
            if (!workspaceId.equals(collection.getWorkspaceId())) throw AuthoringException.notFound();
            return new AuthorizationScope(actor.tenantId(), workspaceId, repositoryId);
        });
        authority.require(actor, scope, true);
        var decision = fresh.requireAllowed(scope, token, correlationId, "DOCUMENT_AUTHOR");
        return write(actor, () -> {
            var collection = repository.collection(collectionId, actor.tenantId(), true);
            if (!workspaceId.equals(collection.getWorkspaceId())) throw new ReviewRevisionConflictException();
            requireActive(collection.getStatus());
            requireDecision(scope, decision);
            authority.require(actor, scope, true);
            var document = new DocumentEntity(actor.tenantId(), collectionId, repositoryId, title.strip(), actor.identityId());
            repository.create(document);
            audit(document, null, scope, actor, decision, "DOCUMENT_CREATED", correlationId);
            return repository.details(document, scope);
        });
    }
    @Override
    public DocumentDetails document(UUID id, S01AccessContext actor) {
        return read(actor, () -> {
            var document = repository.document(id, actor.tenantId(), false);
            var scope = repository.scope(document, false);
            authority.require(actor, scope, false);
            return repository.details(document, scope);
        });
    }
    @Override
    public VersionPage versions(UUID id, int page, int size, S01AccessContext actor) {
        if (page < 0 || size < 1 || size > 100 || page > Integer.MAX_VALUE / size)
            throw new AuthoringException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid page or size.");
        return read(actor, () -> {
            var document = repository.document(id, actor.tenantId(), false);
            var scope = repository.scope(document, false);
            authority.require(actor, scope, false);
            return repository.page(document, scope, page, size);
        });
    }
    @Override
    public VersionDetails createDraft(UUID id, String content, UUID snapshotId,
            S01AccessContext actor, String token, String correlationId) {
        var scope = authorScope(id, actor);
        var decision = fresh.requireAllowed(scope, token, correlationId, "DOCUMENT_AUTHOR");
        var snapshot = snapshots.resolve(snapshotId, scope);
        validateSnapshot(snapshotId, snapshot, scope);
        return write(actor, () -> {
            var document = repository.document(id, actor.tenantId(), true);
            var actual = repository.scope(document, true);
            requireDecision(actual, decision);
            authority.require(actor, actual, true);
            requireActive(document.getStatus());
            requireActive(repository.collection(document.getCollectionId(), actor.tenantId(), true).getStatus());
            var version = DocumentVersionEntity.draft(actor.tenantId(), id, repository.nextVersion(document), actor.identityId(), snapshot);
            repository.create(version);
            repository.content(version, content, actor.identityId());
            repository.attach(version, snapshot);
            audit(document, version, actual, actor, decision, "DOCUMENT_VERSION_CREATED", correlationId);
            return repository.details(version, actual);
        });
    }
    @Override
    public VersionDetails version(UUID id, S01AccessContext actor) {
        return read(actor, () -> {
            var version = repository.version(id, actor.tenantId(), false);
            var document = repository.document(version.getDocumentId(), actor.tenantId(), false);
            var scope = repository.scope(document, false);
            authority.require(actor, scope, false);
            return repository.details(version, scope);
        });
    }
    @Override
    public VersionDetails edit(UUID id, String content, long expected,
            S01AccessContext actor, String token, String correlationId) {
        var scope = read(actor, () -> {
            var version = repository.version(id, actor.tenantId(), false);
            var resolved = repository.scope(repository.document(version.getDocumentId(), actor.tenantId(), false), false);
            authority.require(actor, resolved, true);
            return resolved;
        });
        var decision = fresh.requireAllowed(scope, token, correlationId, "DOCUMENT_AUTHOR");
        return write(actor, () -> {
            var version = repository.version(id, actor.tenantId(), true);
            var document = repository.document(version.getDocumentId(), actor.tenantId(), false);
            var actual = repository.scope(document, true);
            requireDecision(actual, decision);
            authority.require(actor, actual, true);
            requireActive(document.getStatus());
            requireActive(repository.collection(document.getCollectionId(), actor.tenantId(), true).getStatus());
            if (expected != version.getRevision()) throw new ReviewRevisionConflictException();
            if (version.getState() != DocumentState.DRAFT || version.getReviewStage() != null)
                throw new AuthoringException(HttpStatus.CONFLICT, "STATE_TRANSITION_FORBIDDEN", "Only a Draft may be edited.");
            repository.content(version, content, actor.identityId());
            repository.bumpRevision(version);
            audit(document, version, actual, actor, decision, "DOCUMENT_CONTENT_UPDATED", correlationId);
            return repository.details(version, actual);
        });
    }
    @Override
    public VersionEvidence evidence(UUID id, S01AccessContext actor) {
        return read(actor, () -> {
            var version = repository.version(id, actor.tenantId(), false);
            var document = repository.document(version.getDocumentId(), actor.tenantId(), false);
            var scope = repository.scope(document, false);
            authority.require(actor, scope, false);
            return new VersionEvidence(id, repository.binding(version, scope), repository.evidence(actor.tenantId(), id));
        });
    }
    private AuthorizationScope authorScope(UUID id, S01AccessContext actor) {
        return read(actor, () -> {
            var scope = repository.scope(repository.document(id, actor.tenantId(), false), false);
            authority.require(actor, scope, true);
            return scope;
        });
    }
    private void validateSnapshot(UUID id, SourceSnapshot snapshot, AuthorizationScope scope) {
        if (snapshot == null || !id.equals(snapshot.id()) || !scope.equals(snapshot.scope())
                || snapshot.headSha() == null || !snapshot.headSha().matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
                || !new SubmitResult.Binding(snapshot.targetKind(), snapshot.targetRef(), snapshot.comparisonSha(),
                        snapshot.configRevision(), snapshot.capturedAt()).complete() || snapshot.evidence().isEmpty()
                || snapshot.evidence().stream().anyMatch(e -> e.type() == null || e.type().isBlank()
                        || e.type().length() > 64 || (blank(e.sourceRef()) && blank(e.artifactRef())))) {
            throw AuthoringException.sourceUnavailable();
        }
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private void requireDecision(AuthorizationScope scope, AuthorizationDecision decision) {
        if (!"DOCUMENT_AUTHOR".equals(decision.action()) || !scope.tenantId().equals(decision.tenantId())
                || !scope.workspaceId().equals(decision.workspaceId())
                || !Objects.equals(scope.repositoryConnectionId(), decision.repositoryConnectionId()))
            throw new ReviewRevisionConflictException();
    }
    private void requireActive(String status) {
        if (!"ACTIVE".equals(status)) throw new AuthoringException(HttpStatus.CONFLICT, "RESOURCE_INACTIVE", "Document or collection is inactive.");
    }
    private void audit(DocumentEntity document, DocumentVersionEntity version, AuthorizationScope scope,
            S01AccessContext actor, AuthorizationDecision decision, String action, String correlationId) {
        audits.saveAndFlush(DocumentAuditEntity.builder().id(UUID.randomUUID()).tenantId(actor.tenantId())
                .documentId(document.getId()).versionId(version == null ? null : version.getId())
                .workspaceId(scope.workspaceId()).repositoryConnectionId(scope.repositoryConnectionId())
                .actorRef(actor.identityId().toString()).action(action)
                .previousState("DOCUMENT_CONTENT_UPDATED".equals(action) ? DocumentState.DRAFT : null)
                .newState(version == null ? null : DocumentState.DRAFT).sourceSha(version == null ? null : version.getSourceSha())
                .metadataJson(json.writeValueAsString(Map.of("authorization", decision, "correlationId", correlationId)))
                .occurredAt(Instant.now()).build());
    }
    private <T> T read(S01AccessContext actor, Supplier<T> operation) {
        var transaction = new TransactionTemplate(transactions);
        transaction.setReadOnly(true);
        return transaction.execute(status -> { repository.tenant(actor.tenantId()); return operation.get(); });
    }
    private <T> T write(S01AccessContext actor, Supplier<T> operation) {
        return new TransactionTemplate(transactions).execute(status -> { repository.tenant(actor.tenantId()); return operation.get(); });
    }
}
