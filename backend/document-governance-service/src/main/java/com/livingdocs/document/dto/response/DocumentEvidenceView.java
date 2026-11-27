package com.livingdocs.document.dto.response;

import java.util.*;
import lombok.*;

@Getter
@AllArgsConstructor
public class DocumentEvidenceView {
    private final UUID versionId;
    private final ReviewResponse.SourceBinding sourceBinding;
    private final List<EvidenceView> evidence;

    @Getter
    @AllArgsConstructor
    public static class EvidenceView {
        private final UUID evidenceId;
        private final String type;
        private final String sourceRef;
        private final String artifactRef;
    }
}
