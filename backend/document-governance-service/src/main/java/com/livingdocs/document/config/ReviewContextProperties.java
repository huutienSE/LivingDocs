package com.livingdocs.document.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component @ConfigurationProperties("livingdocs.review-context") @Validated
@Getter @Setter
public class ReviewContextProperties {
    @Min(1) private int queryTimeoutMs = 2000;
}
