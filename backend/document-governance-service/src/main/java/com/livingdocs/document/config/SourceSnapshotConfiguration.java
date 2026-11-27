package com.livingdocs.document.config;

import com.livingdocs.document.client.SourceSnapshotProvider;
import com.livingdocs.document.exception.AuthoringException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

@Configuration
public class SourceSnapshotConfiguration {
    @Bean
    @ConditionalOnMissingBean(SourceSnapshotProvider.class)
    SourceSnapshotProvider sourceSnapshotProvider() {
        return (id, scope) -> { throw AuthoringException.sourceUnavailable(); };
    }
}
