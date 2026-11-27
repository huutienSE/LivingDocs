package com.livingdocs.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/** Reviewed wire schemas complement concrete Controller DTOs; HTTP evidence checks conformance. */
@Configuration(proxyBeanMethods = false)
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT",
        description = "S01 human JWT: RS256, issuer urn:livingdocs:s01, audience livingdocs-api")
public class GitHubAuthorizationHttpConfiguration {
    @Bean
    OpenApiCustomizer githubAuthorizationContract() {
        return api -> {
            final OpenAPI contract;
            try (var input = new ClassPathResource("openapi/liv151-authorization.json").getInputStream()) {
                contract = io.swagger.v3.core.util.Json.mapper().readValue(input, OpenAPI.class);
            } catch (java.io.IOException malformed) { throw new IllegalStateException("GitHub authorization contract is unavailable", malformed); }
            contract.getComponents().getSchemas().forEach(api.getComponents()::addSchemas);
            contract.getComponents().getResponses().forEach(api.getComponents()::addResponses);
            Schema context = contract.getComponents().getSchemas().get("GitHubAuthorizationContext");
            api.getComponents().addSchemas("GitHubAuthorizationContextView", context);
            contract.getPaths().forEach((path, template) -> {
                var actual = api.getPaths().get(path);
                if (actual == null) return;
                template.readOperationsMap().forEach((method, operation) -> {
                    var target = actual.readOperationsMap().get(method);
                    if (target == null || !operation.getOperationId().equals(target.getOperationId())) return;
                    target.setParameters(operation.getParameters());
                    target.setRequestBody(operation.getRequestBody());
                    target.setResponses(operation.getResponses());
                    target.setSecurity(operation.getSecurity());
                    target.setExtensions(operation.getExtensions());
                    target.setDescription(operation.getDescription());
                    target.setSummary(operation.getSummary());
                    target.setTags(operation.getTags());
                });
            });
        };
    }
}
