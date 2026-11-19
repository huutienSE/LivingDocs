package com.livingdocs.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;

@Configuration(proxyBeanMethods = false)
@SecurityScheme(name = "serviceAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT",
        description = "Internal workload identity; production access denied until issuer/claim contract is configured")
public class SourceHttpConfiguration {
    @Bean
    JsonMapperBuilderCustomizer sourceJsonContract() {
        return builder -> builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .withCoercionConfig(LogicalType.Textual, coercion -> coercion
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }

    @Bean
    OpenApiCustomizer sourceErrorContract() {
        return api -> {
            var problem = new ObjectSchema();
            for (String field : List.of("type", "title", "detail", "instance", "code", "correlationId")) {
                var property = new StringSchema();
                if (field.equals("type") || field.equals("instance")) property.setFormat("uri");
                problem.addProperty(field, property);
            }
            problem.addProperty("status", new IntegerSchema());
            problem.setRequired(List.of("type", "title", "status", "detail", "instance", "code", "correlationId"));
            api.getComponents().addSchemas("Problem", problem);
            api.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                if (!List.of("api069_post", "api070_post").contains(operation.getOperationId())) return;
                operation.getResponses().get("200").setHeaders(sourceHeaders());
                for (String status : List.of("400", "401", "403", "404", "405", "406", "409", "413", "415", "500", "503")) {
                    var headers = sourceHeaders();
                    if (status.equals("401")) headers.put("WWW-Authenticate", fixedHeader("Bearer"));
                    if (status.equals("405")) headers.put("Allow", fixedHeader("POST"));
                    operation.getResponses().addApiResponse(status, new ApiResponse()
                            .description("Fail-closed source operation error")
                            .headers(headers).content(new Content().addMediaType("application/problem+json",
                                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/Problem")))));
                }
            }));
        };
    }

    private static Map<String, Header> sourceHeaders() {
        Map<String, Header> headers = new LinkedHashMap<>();
        headers.put("Cache-Control", fixedHeader("no-store"));
        headers.put("X-Correlation-ID", new Header().required(true)
                .schema(new StringSchema().pattern("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")));
        return headers;
    }

    private static Header fixedHeader(String value) {
        var schema = new StringSchema();
        schema.setEnum(List.of(value));
        return new Header().required(true).schema(schema);
    }
}
