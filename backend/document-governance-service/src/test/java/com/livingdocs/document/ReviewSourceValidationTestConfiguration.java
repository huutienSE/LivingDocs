package com.livingdocs.document;

import com.livingdocs.document.domain.model.*;
import com.livingdocs.document.application.service.SourceReviewValidator;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Test-only source authority, explicitly imported; never proves a real S02/GitHub integration. */
@TestConfiguration(proxyBeanMethods = false)
class ReviewSourceValidationTestConfiguration {
    static final Map<UUID, String> HEADS = new ConcurrentHashMap<>();

    @Bean
    TestValidator sourceReviewValidator() { return new TestValidator(); }

    static class TestValidator implements SourceReviewValidator {
        final Map<UUID, Function<DocumentVersion, SourceReviewValidation>> overrides = new ConcurrentHashMap<>();
        private final Map<UUID, AtomicInteger> calls = new ConcurrentHashMap<>();

        int calls(UUID versionId) { return calls.getOrDefault(versionId, new AtomicInteger()).get(); }

        @Override
        public SourceReviewValidation validate(DocumentVersion version) {
            calls.computeIfAbsent(version.id(), ignored -> new AtomicInteger()).incrementAndGet();
            if (overrides.containsKey(version.id())) return overrides.get(version.id()).apply(version);
            var now = Instant.now();
            return new SourceReviewValidation("test-validation-" + version.id(), version.tenantId(), version.workspaceId(),
                    version.id(), version.revision(), version.repositoryConnectionId(),
                    HEADS.get(version.repositoryConnectionId()), now, now.plusSeconds(60));
        }
    }
}
