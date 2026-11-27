package com.livingdocs.document.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Getter;

@Getter
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"items", "page", "size", "totalElements"})
public class ReviewQueueView {
    private final List<ReviewQueueItem> items;
    @Schema(minimum = "0") private final int page;
    @Schema(minimum = "1") private final int size;
    @Schema(minimum = "0") private final long totalElements;

    public ReviewQueueView(List<ReviewQueueItem> items, int page, int size, long totalElements) {
        this.items = List.copyOf(items);
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
    }
}
