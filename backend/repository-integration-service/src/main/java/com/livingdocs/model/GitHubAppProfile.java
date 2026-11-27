package com.livingdocs.model;

import java.util.Map;
import java.util.Set;
import lombok.Getter;

@Getter
public final class GitHubAppProfile {
    private final String appId;
    private final String slug;
    private final String name;
    private final String clientId;
    private final Map<String, String> permissions;
    private final Set<String> events;
    public GitHubAppProfile(String appId, String slug, String name, String clientId, Map<String,String> permissions, Set<String> events) {
        this.appId=appId; this.slug=slug; this.name=name; this.clientId=clientId;
        this.permissions=Map.copyOf(permissions); this.events=Set.copyOf(events);
    }
}
