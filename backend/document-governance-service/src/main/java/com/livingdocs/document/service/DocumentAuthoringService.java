package com.livingdocs.document.service;

import com.livingdocs.document.model.*;
import java.util.UUID;

public interface DocumentAuthoringService {
    DocumentDetails createDocument(UUID workspaceId, UUID collectionId, UUID repositoryId, String title,
            S01AccessContext actor, String token, String correlationId);
    DocumentDetails document(UUID id, S01AccessContext actor);
    VersionPage versions(UUID documentId, int page, int size, S01AccessContext actor);
    VersionDetails createDraft(UUID documentId, String content, UUID snapshotId,
            S01AccessContext actor, String token, String correlationId);
    VersionDetails version(UUID id, S01AccessContext actor);
    VersionDetails edit(UUID id, String content, long revision,
            S01AccessContext actor, String token, String correlationId);
    VersionEvidence evidence(UUID id, S01AccessContext actor);
}
