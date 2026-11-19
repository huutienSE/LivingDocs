package com.livingdocs.config;

import com.livingdocs.repository.ConfigurationRevisionLookup;
import com.livingdocs.repository.ConnectionLookup;
import com.livingdocs.repository.GithubConnectionRepository;
import com.livingdocs.repository.JpaConnectionLookup;
import com.livingdocs.repository.RepositoryConnectionRepository;
import com.livingdocs.repository.SourceTargetHeadRepository;
import com.livingdocs.repository.TrackedBranchRepository;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Kết nối thành phần truy xuất dữ liệu; thiếu cấu hình phải dẫn tới từ chối xác minh. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConnectionLookupProperties.class)
public class ConnectionLookupConfiguration {
    @Bean
    @ConditionalOnMissingBean(ConfigurationRevisionLookup.class)
    ConfigurationRevisionLookup configurationRevisionLookup(RepositoryConnectionRepository repositories) {
        return (scope, id) -> repositories.findConfigurationRevision(scope.tenantId(), scope.workspaceId(), id);
    }

    @Bean
    @ConditionalOnMissingBean(ConnectionLookup.class)
    JpaConnectionLookup connectionLookup(EntityManager entityManager, RepositoryConnectionRepository repositories,
                                         GithubConnectionRepository githubConnections, TrackedBranchRepository trackedBranches,
                                         SourceTargetHeadRepository sourceHeads,
                                         ConfigurationRevisionLookup revisions, ConnectionLookupProperties properties) {
        return new JpaConnectionLookup(entityManager, repositories, githubConnections, trackedBranches, sourceHeads, revisions,
                properties.githubReadyStatuses());
    }
}
