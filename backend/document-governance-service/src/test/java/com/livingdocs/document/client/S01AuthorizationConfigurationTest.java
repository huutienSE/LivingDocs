package com.livingdocs.document.client;

import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class S01AuthorizationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(JsonMapper.class, JsonMapper::new)
            .withUserConfiguration(S01FreshAuthorizationClient.class);

    @Test void legacyPropertiesRemainUsable() {
        runner.withPropertyValues("livingdocs.review.service-token-uri=https://legacy.test/tokens",
                "livingdocs.review.authorization-uri=https://legacy.test/evaluate",
                "livingdocs.review.workload-secret-path=legacy.secret").run(context -> {
                    assertThat(context).hasNotFailed();
                    var client = context.getBean(S01FreshAuthorizationClient.class);
                    assertThat(ReflectionTestUtils.getField(client, "tokenEndpoint")).isEqualTo(URI.create("https://legacy.test/tokens"));
                    assertThat(ReflectionTestUtils.getField(client, "secretPath")).isEqualTo(Path.of("legacy.secret"));
                });
    }

    @Test void sharedNamespaceOverridesLegacyProperties() {
        runner.withPropertyValues("livingdocs.review.service-token-uri=https://legacy.test/tokens",
                "livingdocs.review.authorization-uri=https://legacy.test/evaluate",
                "livingdocs.review.workload-secret-path=legacy.secret",
                "livingdocs.authorization.service-token-uri=https://shared.test/tokens",
                "livingdocs.authorization.authorization-uri=https://shared.test/evaluate",
                "livingdocs.authorization.workload-secret-path=shared.secret").run(context -> {
                    assertThat(context).hasNotFailed();
                    var client = context.getBean(S01FreshAuthorizationClient.class);
                    assertThat(ReflectionTestUtils.getField(client, "tokenEndpoint")).isEqualTo(URI.create("https://shared.test/tokens"));
                    assertThat(ReflectionTestUtils.getField(client, "secretPath")).isEqualTo(Path.of("shared.secret"));
                });
    }

    @Test void sharedFalseCannotBeOverriddenByLegacyInsecureTransportFlag() {
        runner.withPropertyValues("livingdocs.review.allow-insecure-http=true",
                "livingdocs.authorization.allow-insecure-http=false",
                "livingdocs.authorization.service-token-uri=http://shared.test/tokens",
                "livingdocs.authorization.authorization-uri=http://shared.test/evaluate")
                .run(context -> assertThat(context).hasFailed());
    }
}
