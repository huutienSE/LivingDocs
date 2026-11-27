package com.livingdocs.document.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component @ConfigurationProperties("livingdocs.review-queue") @Validated
@Getter @Setter
public class ReviewQueueProperties {
    private String accessContextUri = "";
    private boolean allowInsecureHttp;
    @Min(1) private int defaultPageSize = 20;
    @Min(1) private int maxPageSize = 100;
    @Min(1) private int queryTimeoutMs = 2000;

    @AssertTrue(message = "default page size must not exceed max page size")
    public boolean isPageBudgetValid() { return defaultPageSize <= maxPageSize; }
}
