package com.livingdocs.document.dto.response;

import java.util.List;
import lombok.*;

@Getter
@AllArgsConstructor
public class DocumentVersionsView {
    private final List<AuthoringVersionView> items;
    private final int page;
    private final int size;
    private final long totalElements;
}
