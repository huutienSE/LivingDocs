package com.livingdocs.document.dto.response;

import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
public class DocumentView {
    private UUID documentId;
    private UUID collectionId;
    private UUID workspaceId;
    private UUID repositoryConnectionId;
    private String title;
    private String status;
    @io.swagger.v3.oas.annotations.media.Schema(nullable = true)
    private UUID currentVersionId;
}
