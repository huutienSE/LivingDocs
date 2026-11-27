package com.livingdocs.model;

import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter @RequiredArgsConstructor
public final class GitHubAuthorizationCallbackResult {
    private final UUID contextId;
    private final String completionUrl;
}
