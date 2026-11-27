package com.livingdocs.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter @Schema(requiredProperties = {"items", "page", "hasNext"})
public class GitHubRepositoryListView {
    private final List<Item> items;
    private final int page;
    private final boolean hasNext;
    public GitHubRepositoryListView(List<Item> items, int page, boolean hasNext) {
        this.items = List.copyOf(items); this.page = page; this.hasNext = hasNext;
    }
    @Getter
    @Schema(name = "GitHubRepositoryItem", requiredProperties = {"repositoryId", "fullName", "canRead"})
    public static final class Item {
        private final String repositoryId;
        private final String fullName;
        @Schema(allowableValues = "true") private final boolean canRead;
        public Item(String repositoryId, String fullName, boolean canRead) {
            this.repositoryId = repositoryId; this.fullName = fullName; this.canRead = canRead;
        }
    }
}
