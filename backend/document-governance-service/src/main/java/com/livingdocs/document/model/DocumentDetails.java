package com.livingdocs.document.model;
import java.util.UUID;

public record DocumentDetails(UUID documentId, UUID collectionId, UUID workspaceId,
        UUID repositoryConnectionId, String title, String status, UUID currentVersionId) {}
