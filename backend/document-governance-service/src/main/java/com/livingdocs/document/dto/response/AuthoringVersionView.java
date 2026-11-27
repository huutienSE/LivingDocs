package com.livingdocs.document.dto.response;

import com.livingdocs.document.model.DocumentState;
import com.livingdocs.document.model.ReviewStage;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
public class AuthoringVersionView {
    private UUID documentId;
    private UUID versionId;
    private int versionNo;
    private DocumentState state;
    @io.swagger.v3.oas.annotations.media.Schema(nullable = true)
    private ReviewStage reviewStage;
    private long revision;
    private String creatorActorRef;
    private UUID initiatedByIdentityId;
    @io.swagger.v3.oas.annotations.media.Schema(nullable = true)
    private String content;
    @io.swagger.v3.oas.annotations.media.Schema(nullable = true)
    private String contentRef;
    private boolean contentAvailable;
    @io.swagger.v3.oas.annotations.media.Schema(nullable = true)
    private String contentFormat;
    private ReviewResponse.SourceBinding sourceBinding;
}
