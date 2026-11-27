package com.livingdocs.model;

import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

public final class GitHubAuthorizationDiscovery {
    private GitHubAuthorizationDiscovery() {}

    @Getter
    public static final class Page<T> {
        private final List<T> items;
        private final int page;
        private final boolean hasNext;
        public Page(List<T> items, int page, boolean hasNext) {
            this.items = List.copyOf(items); this.page = page; this.hasNext = hasNext;
        }
    }
    @Getter @RequiredArgsConstructor
    public static final class Installation {
        private final String installationId;
        private final String accountId;
        private final String accountLogin;
        private final String repositorySelection;
        private final boolean suspended;
    }
    @Getter @RequiredArgsConstructor
    public static final class Repository {
        private final String repositoryId;
        private final String fullName;
        private final boolean canRead;
    }
}
