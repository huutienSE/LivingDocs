package com.livingdocs.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import lombok.Value;
import lombok.experimental.Accessors;

@ConfigurationProperties(prefix = "livingdocs.source.lookup")
@Value
@Accessors(fluent = true)
public class ConnectionLookupProperties {
    private final Set<String> githubReadyStatuses;

    public ConnectionLookupProperties(Set<String> githubReadyStatuses) {
        this.githubReadyStatuses = githubReadyStatuses == null ? Set.of() : Set.copyOf(githubReadyStatuses);
    }
}
