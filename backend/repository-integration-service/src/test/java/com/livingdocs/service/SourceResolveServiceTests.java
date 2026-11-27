package com.livingdocs.service;

import com.livingdocs.client.GitHubSourceClient.CommitObservation;
import com.livingdocs.config.SourceRequestProperties;
import com.livingdocs.model.SourceConnection;
import com.livingdocs.model.SourceResolveRequest;
import com.livingdocs.model.SourceScope;
import com.livingdocs.model.TargetKind;
import com.livingdocs.model.ValidationReason;
import com.livingdocs.repository.JpaSourceObservationStore;
import com.livingdocs.service.impl.SourceRequestExecutor;
import com.livingdocs.service.impl.SourceResolveServiceImpl;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SourceResolveServiceTests {
    @Test
    void expiredLookupCannotWriteAnObservation() {
        var scope = new SourceScope(UUID.randomUUID(), UUID.randomUUID());
        var repository = UUID.randomUUID();
        var request = new SourceResolveRequest(repository, TargetKind.BRANCH, "refs/heads/main", null);
        var github = mock(GitHubSourceLookup.class);
        var store = mock(JpaSourceObservationStore.class);
        var nanos = new AtomicLong();
        var commit = mock(CommitObservation.class);
        try (var requests = new SourceRequestExecutor(new SourceRequestProperties(Duration.ofSeconds(1)), 1, nanos::get)) {
            when(github.readCurrent(scope, repository, TargetKind.BRANCH, request.targetRef(), null))
                    .thenAnswer(invocation -> {
                        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
                        return new GitHubSourceLookup.Result(GitHubSourceLookup.Outcome.OBSERVED,
                                new SourceConnection(scope, repository, "cfg-1", true),
                                commit, "123", 1);
                    });
            var resolver = new SourceResolveServiceImpl(github, store, requests);
            assertEquals(ValidationReason.SOURCE_UNAVAILABLE, resolver.resolve(scope, request).reason());
            verifyNoInteractions(store);
        }
    }
}
