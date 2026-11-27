package com.livingdocs.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter @Schema(requiredProperties = {"items", "page", "hasNext"})
public class GitHubInstallationListView {
    private final List<Item> items;
    private final int page;
    private final boolean hasNext;
    public GitHubInstallationListView(List<Item> items, int page, boolean hasNext) {
        this.items = List.copyOf(items); this.page = page; this.hasNext = hasNext;
    }
    @Getter
    @Schema(name = "GitHubInstallationItem", requiredProperties = {"installationId", "accountId", "accountLogin", "repositorySelection", "suspended"})
    public static final class Item {
        private final String installationId;
        private final String accountId;
        private final String accountLogin;
        private final String repositorySelection;
        private final boolean suspended;
        public Item(String installationId, String accountId, String accountLogin, String repositorySelection, boolean suspended) {
            this.installationId = installationId; this.accountId = accountId; this.accountLogin = accountLogin;
            this.repositorySelection = repositorySelection; this.suspended = suspended;
        }
    }
}
