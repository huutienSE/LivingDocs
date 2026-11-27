package com.livingdocs.document;

import com.livingdocs.document.client.SourceSnapshotProvider;
import com.livingdocs.document.exception.AuthoringException;
import com.livingdocs.document.model.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.support.GenericApplicationContext;

/** Explicit test-classpath launcher for the disposable Gateway/Newman demo. */
public final class AuthoringFixtureApplication {
    private AuthoringFixtureApplication() {}

    public static void main(String[] args) {
        var application = new SpringApplication(DocumentGovernanceServiceApplication.class);
        application.addInitializers(context -> {
            var env = context.getEnvironment();
            var scope = new AuthorizationScope(UUID.fromString(env.getRequiredProperty("liv164.fixture.tenant")),
                    UUID.fromString(env.getRequiredProperty("liv164.fixture.workspace")),
                    UUID.fromString(env.getRequiredProperty("liv164.fixture.repository")));
            UUID id = UUID.fromString(env.getRequiredProperty("liv164.fixture.snapshot"));
            var snapshot = new SourceSnapshot(id, scope, "a".repeat(40), "TRACKED_BRANCH", "refs/heads/main", null,
                    "CONTROLLED_FIXTURE", Instant.now().minusSeconds(1),
                    List.of(new SourceSnapshot.Evidence("SOURCE", "https://example.test/fixture/" + "a".repeat(40), null)));
            ((GenericApplicationContext) context).registerBean("liv164TestSnapshots", SourceSnapshotProvider.class,
                    () -> (requested, actual) -> {
                        if (!id.equals(requested) || !scope.equals(actual)) throw AuthoringException.sourceUnavailable();
                        return snapshot;
                    });
        });
        application.run(args);
    }
}
