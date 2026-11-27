package com.livingdocs.document.repository;

import com.livingdocs.document.entity.*;
import com.livingdocs.document.exception.*;
import com.livingdocs.document.model.*;
import jakarta.persistence.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class DocumentAuthoringRepository {
    private final EntityManager entities;
    private final DocumentVersionRepository scopeRepository;
    private final DocumentJpaRepository documents;
    private final DocumentVersionJpaRepository versions;
    private final ContentRevisionJpaRepository contents;
    private final DocumentEvidenceJpaRepository evidence;

    public void tenant(UUID tenantId) { scopeRepository.setTenant(tenantId); }

    public DocumentCollectionEntity collection(UUID id, UUID tenant, boolean lock) {
        var value = entities.find(DocumentCollectionEntity.class, id,
                lock ? LockModeType.PESSIMISTIC_READ : LockModeType.NONE);
        if (value == null || !tenant.equals(value.getTenantId())) throw AuthoringException.notFound();
        return value;
    }
    public DocumentEntity document(UUID id, UUID tenant, boolean lock) {
        var value = entities.find(DocumentEntity.class, id,
                lock ? LockModeType.PESSIMISTIC_WRITE : LockModeType.NONE);
        if (value == null || !tenant.equals(value.getTenantId())) throw AuthoringException.notFound();
        return value;
    }
    public AuthorizationScope scope(DocumentEntity document, boolean lock) {
        var collection = collection(document.getCollectionId(), document.getTenantId(), lock);
        return new AuthorizationScope(document.getTenantId(), collection.getWorkspaceId(), document.getRepositoryConnectionId());
    }
    public DocumentVersionEntity version(UUID id, UUID tenant, boolean lock) {
        // Reuse the same scope locks as Submit/Staff Review.
        scopeRepository.find(id, tenant, lock).orElseThrow(AuthoringException::notFound);
        return versions.findById(id).orElseThrow(AuthoringException::notFound);
    }
    public DocumentDetails details(DocumentEntity document, AuthorizationScope scope) {
        var current = entities.createQuery("select v.id from DocumentVersionEntity v where v.tenantId = :tenant and v.documentId = :document order by v.versionNo desc", UUID.class)
                .setParameter("tenant", document.getTenantId()).setParameter("document", document.getId())
                .setMaxResults(1).getResultList();
        return new DocumentDetails(document.getId(), document.getCollectionId(), scope.workspaceId(),
                document.getRepositoryConnectionId(), document.getTitle(), document.getStatus(), current.isEmpty() ? null : current.getFirst());
    }
    public VersionDetails details(DocumentVersionEntity version, AuthorizationScope scope) {
        var content = contents.findFirstByTenantIdAndVersionIdOrderByRevisionNoDesc(version.getTenantId(), version.getId()).orElse(null);
        String markdown = content == null ? null : content.getContent();
        return new VersionDetails(version.getDocumentId(), version.getId(), version.getVersionNo(), version.getState(),
                version.getReviewStage(), version.getRevision(), version.getCreatorActorRef(), version.getInitiatedByIdentityId(),
                markdown, content == null ? null : content.getContentRef(), markdown != null, markdown == null ? null : "MARKDOWN", binding(version, scope));
    }
    public VersionDetails.Binding binding(DocumentVersionEntity v, AuthorizationScope scope) {
        if (scope.repositoryConnectionId() == null) return null;
        return new VersionDetails.Binding(scope.repositoryConnectionId(), v.getSourceTargetKind(), v.getSourceTargetRef(),
                v.getSourceSha(), v.getSourceComparisonSha(), v.getSourceConfigRevision(), v.getSourceCapturedAt());
    }
    public VersionPage page(DocumentEntity document, AuthorizationScope scope, int page, int size) {
        var query = entities.createQuery("select v from DocumentVersionEntity v where v.tenantId = :tenant and v.documentId = :document order by v.versionNo desc", DocumentVersionEntity.class)
                .setParameter("tenant", document.getTenantId()).setParameter("document", document.getId());
        var items = query.setFirstResult(Math.multiplyExact(page, size)).setMaxResults(size).getResultList();
        long total = entities.createQuery("select count(v) from DocumentVersionEntity v where v.tenantId = :tenant and v.documentId = :document", Long.class)
                .setParameter("tenant", document.getTenantId()).setParameter("document", document.getId()).getSingleResult();
        return new VersionPage(items.stream().map(v -> details(v, scope)).toList(), page, size, total);
    }
    public int nextVersion(DocumentEntity document) {
        Integer maximum = entities.createQuery("select max(v.versionNo) from DocumentVersionEntity v where v.tenantId = :tenant and v.documentId = :document", Integer.class)
                .setParameter("tenant", document.getTenantId()).setParameter("document", document.getId()).getSingleResult();
        return maximum == null ? 1 : Math.incrementExact(maximum);
    }
    public void create(DocumentEntity document) { documents.saveAndFlush(document); }
    public void create(DocumentVersionEntity version) { entities.persist(version); entities.flush(); }
    public void content(DocumentVersionEntity version, String markdown, UUID actor) {
        var previous = contents.findFirstByTenantIdAndVersionIdOrderByRevisionNoDesc(version.getTenantId(), version.getId());
        int number = previous.map(c -> Math.incrementExact(c.getRevisionNo())).orElse(1);
        contents.saveAndFlush(new ContentRevisionEntity(version.getTenantId(), version.getId(), number, markdown, actor));
    }
    public void bumpRevision(DocumentVersionEntity version) {
        entities.lock(version, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        entities.flush();
    }
    public List<VersionEvidence.Item> evidence(UUID tenant, UUID version) {
        return entities.createQuery("select e from DocumentEvidenceEntity e where e.tenantId = :tenant and e.versionId = :version order by e.id", DocumentEvidenceEntity.class)
                .setParameter("tenant", tenant).setParameter("version", version).getResultList().stream()
                .map(e -> new VersionEvidence.Item(e.getId(), e.getType(), e.getSourceRef(), e.getArtifactRef())).toList();
    }
    public void attach(DocumentVersionEntity version, SourceSnapshot snapshot) {
        for (var item : snapshot.evidence()) entities.persist(new DocumentEvidenceEntity(version.getTenantId(), version.getId(), item));
        entities.flush();
    }
}
