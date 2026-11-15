package com.livingdocs.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@RequiredArgsConstructor
@ConfigurationProperties("livingdocs.source.github")
public final class GitHubAppProperties {
    private final String clientId;
    private final String keyDirectory;
}
