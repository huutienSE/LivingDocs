package com.livingdocs.config;

import java.time.Duration;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Ngân sách tổng cho API-069/070, được lấy từ cấu hình môi trường. */
@Getter
@ConfigurationProperties("livingdocs.source")
public final class SourceRequestProperties {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private final Duration requestTimeout;

    public SourceRequestProperties(Duration requestTimeout) {
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("Source request timeout must be positive");
        }
        requestTimeout.toNanos();
        this.requestTimeout = requestTimeout;
    }
}
