package com.livingdocs.document.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenApiCustomizer nullableEnums() {
        return api -> {
            for (Schema<?> schema : api.getComponents().getSchemas().values()) {
                if (schema.getProperties() != null) {
                    for (Schema<?> property : schema.getProperties().values()) {
                        includeNullInEnum(property);
                    }
                }
            }
        };
    }

    private <T> void includeNullInEnum(Schema<T> schema) {
        if (Boolean.TRUE.equals(schema.getNullable()) && schema.getEnum() != null
                && !schema.getEnum().contains(null)) {
            var values = new ArrayList<>(schema.getEnum());
            values.add(null);
            schema.setEnum(values);
        }
    }

    @Bean
    OpenAPI reviewOpenApi() {
        return new OpenAPI().openapi("3.0.3")
                .info(new Info().title("LivingDocs S04 Document Authoring and Review").version("0.4.0"))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
