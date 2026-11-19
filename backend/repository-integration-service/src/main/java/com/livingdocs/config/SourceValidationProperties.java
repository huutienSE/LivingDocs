package com.livingdocs.config;

import java.time.Duration;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Thời hạn của bằng chứng tính từ lúc bắt đầu xác minh. */
@Getter
@ConfigurationProperties("livingdocs.source.validation")
public final class SourceValidationProperties {
    private final Duration validity;

    public SourceValidationProperties(@DefaultValue("30s") Duration validity) {
        this.validity = validity;
    }
}
