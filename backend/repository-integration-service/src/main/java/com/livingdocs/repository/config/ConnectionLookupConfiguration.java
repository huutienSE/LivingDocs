package com.livingdocs.repository.config;

import com.livingdocs.repository.domain.port.ConfigurationRevisionLookup;
import com.livingdocs.repository.domain.port.ConnectionLookup;
import com.livingdocs.repository.infrastructure.persistence.adapter.JpaConnectionLookup;
import com.livingdocs.repository.infrastructure.persistence.repository.GithubConnectionRepository;
import com.livingdocs.repository.infrastructure.persistence.repository.RepositoryConnectionRepository;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Kết nối thành phần truy xuất dữ liệu; thiếu cấu hình phải dẫn tới từ chối xác minh. */
@Configuration(proxyBeanMethods = false)
public class ConnectionLookupConfiguration {
    @Bean
    @ConditionalOnMissingBean(ConnectionLookup.class)
    ConnectionLookup connectionLookup(EntityManager entityManager, RepositoryConnectionRepository repositories,
                                      GithubConnectionRepository githubConnections,
                                      ObjectProvider<ConfigurationRevisionLookup> revisions,
                                      @Value("${livingdocs.source.lookup.github-ready-statuses:}") Set<String> readyStatuses) {
        // Chưa có cơ chế phiên bản cấu hình trong V1: mặc định không cung cấp bằng chứng.
        ConfigurationRevisionLookup revisionLookup = revisions.getIfAvailable(() -> (scope, id) -> Optional.empty());
        return new JpaConnectionLookup(entityManager, repositories, githubConnections, revisionLookup, readyStatuses);
    }
}
