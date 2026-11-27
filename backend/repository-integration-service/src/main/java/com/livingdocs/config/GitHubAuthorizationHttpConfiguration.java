package com.livingdocs.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT",
        description = "S01 human JWT: RS256, issuer urn:livingdocs:s01, audience livingdocs-api")
public class GitHubAuthorizationHttpConfiguration {
    @Bean
    OpenApiCustomizer githubAuthorizationContract() {
        return api -> {
            var problem = new ObjectSchema();
            for (String field : List.of("type", "title", "detail", "instance", "code", "correlationId")) {
                var property = new StringSchema();
                if (field.equals("type") || field.equals("instance")) property.setFormat("uri");
                problem.addProperty(field, property);
            }
            problem.addProperty("status", new IntegerSchema());
            problem.setRequired(List.of("type", "title", "status", "detail", "instance", "code", "correlationId"));
            api.getComponents().addSchemas("GitHubAuthorizationProblem", problem);
            var path = api.getPaths().get("/api/v1/integrations/github/authorization");
            if (path == null || path.getGet() == null) return;
            var operation = path.getGet();
            operation.addExtension("x-contract-state", "IN_PROGRESS");
            operation.addExtension("x-api-id", "API-046");
            operation.addExtension("x-gateway-exposure", "public");
            operation.setTags(List.of("S02 Public"));
            operation.setDescription("Slice 1: required whole-App profile before user consent. "
                    + "Context-bearing reads fail closed until onboarding is implemented. No granted permissions or repository access are asserted.");
            operation.getResponses().get("200").setHeaders(headers());
            for (String status : List.of("400", "401", "403", "404", "405", "406", "500", "503")) {
                var responseHeaders = headers();
                if (status.equals("401")) responseHeaders.put("WWW-Authenticate", fixed("Bearer"));
                if (status.equals("405")) responseHeaders.put("Allow", fixed("GET, HEAD"));
                operation.getResponses().addApiResponse(status, new ApiResponse()
                        .description("Fail-closed authorization error").headers(responseHeaders)
                        .content(new Content().addMediaType("application/problem+json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/GitHubAuthorizationProblem")))));
            }
        };
    }

    private static Map<String, Header> headers() {
        Map<String, Header> result = new LinkedHashMap<>();
        result.put("Cache-Control", fixed("no-store"));
        result.put("X-Correlation-ID", new Header().required(true)
                .schema(new StringSchema().pattern("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")));
        return result;
    }

    private static Header fixed(String value) {
        var schema = new StringSchema();
        schema.setEnum(List.of(value));
        return new Header().required(true).schema(schema);
    }
}
