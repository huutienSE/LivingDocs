package com.livingdocs.config;

import com.livingdocs.service.GitHubAuthorizationContextService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false) @EnableScheduling @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(prefix = "livingdocs.github.authorization", name = "cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class GitHubAuthorizationMaintenanceConfiguration {
    private final GitHubAuthorizationContextService contexts;
    private final MeterRegistry metrics;

    @Scheduled(initialDelayString = "${livingdocs.github.authorization.cleanup-delay-ms:30000}",
            fixedDelayString = "${livingdocs.github.authorization.cleanup-delay-ms:30000}")
    public void purgeExpiredContexts() {
        try {
            int count = contexts.purgeExpired();
            metrics.counter("livingdocs.github.authorization.contexts.purged").increment(count);
        } catch (RuntimeException unavailable) {
            metrics.counter("livingdocs.github.authorization.cleanup.errors").increment();
            log.warn("GitHub authorization expiry cleanup failed; expired contexts remain unusable");
        }
    }
}
