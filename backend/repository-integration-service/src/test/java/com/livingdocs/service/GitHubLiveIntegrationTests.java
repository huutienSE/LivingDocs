package com.livingdocs.service;

import com.livingdocs.model.SourceResolveRequest;
import com.livingdocs.model.SourceScope;
import com.livingdocs.model.SourceValidationRequest;
import com.livingdocs.model.TargetKind;
import com.livingdocs.model.ValidationReason;
import com.livingdocs.model.ValidationStatus;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm thử chủ động với GitHub và PostgreSQL thật, dùng kết nối thử đã chuẩn bị riêng. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("db")
@EnabledIfSystemProperty(named = "livingdocs.github.live", matches = "true")
class GitHubLiveIntegrationTests {
    private static final Logger LOG = LoggerFactory.getLogger(GitHubLiveIntegrationTests.class);

    @Autowired private SourceResolveService resolver;
    @Autowired private SourceValidationService validator;
    @Autowired private Environment environment;

    @Test
    void resolvesRealBranchAndValidatesMatchingAndDifferentExpectedSha() {
        var scope = new SourceScope(UUID.fromString(required("LIV122_TEST_TENANT_ID")),
                UUID.fromString(required("LIV122_TEST_WORKSPACE_ID")));
        UUID connectionId = UUID.fromString(required("LIV122_TEST_CONNECTION_ID"));
        String ref = required("LIV122_TEST_REF");
        String expectedSha = required("LIV122_TEST_EXPECTED_SHA").toLowerCase(Locale.ROOT);
        assertTrue(expectedSha.matches("[0-9a-f]{40}") && !expectedSha.equals("0".repeat(40)),
                "Điền SHA đầy đủ của đầu nhánh GitHub vào LIV122_TEST_EXPECTED_SHA.");

        var resolved = resolver.resolve(scope, new SourceResolveRequest(connectionId, TargetKind.BRANCH, ref, null));
        assertEquals(ValidationReason.CURRENT, resolved.reason(),
                "Kiểm tra dữ liệu kết nối, quyền App, khóa .pem và kết nối GitHub.");
        assertEquals(expectedSha, resolved.resolvedSha(),
                "Đối chiếu đầu nhánh GitHub và cập nhật LIV122_TEST_EXPECTED_SHA nếu nhánh đã đổi.");
        assertEquals(scope, resolved.scope());
        assertEquals(connectionId, resolved.repositoryConnectionId());
        assertEquals(TargetKind.BRANCH, resolved.targetKind());
        assertEquals(ref, resolved.targetRef());
        assertNotNull(resolved.configRevision());
        assertNotNull(resolved.sourceRevision());
        assertTrue(Long.parseLong(resolved.sourceRevision()) > 0);

        var valid = validator.validate(scope, new SourceValidationRequest(connectionId, TargetKind.BRANCH,
                ref, expectedSha, resolved.configRevision()));
        assertEquals(ValidationStatus.PASS, valid.validationStatus());
        assertEquals(ValidationReason.CURRENT, valid.reason());
        assertEquals(scope.tenantId(), valid.tenantId());
        assertEquals(scope.workspaceId(), valid.workspaceId());
        assertEquals(connectionId, valid.repositoryConnectionId());
        assertEquals(TargetKind.BRANCH, valid.targetKind());
        assertEquals(ref, valid.targetRef());
        assertEquals(expectedSha, valid.validatedHeadSha());
        assertEquals(resolved.configRevision(), valid.configRevision());
        assertEquals(resolved.sourceRevision(), valid.sourceRevision());
        assertNotNull(valid.validationId());
        assertNotNull(valid.checkedAt());
        assertNotNull(valid.expiresAt());
        assertTrue(valid.checkedAt().isBefore(valid.expiresAt()));

        // Thay một ký tự để tạo SHA kỳ vọng hợp lệ về định dạng nhưng khác đầu nhánh.
        String differentSha = expectedSha.substring(0, 39) + (expectedSha.endsWith("a") ? "b" : "a");
        var rejected = validator.validate(scope, new SourceValidationRequest(connectionId, TargetKind.BRANCH,
                ref, differentSha, resolved.configRevision()));
        assertEquals(ValidationStatus.FAIL, rejected.validationStatus());
        assertEquals(ValidationReason.STALE_SHA, rejected.reason());
        assertEquals(expectedSha, rejected.validatedHeadSha());
        assertEquals(resolved.sourceRevision(), rejected.sourceRevision());

        LOG.info("LIV-122 LIVE PASS ref={} sha={} sourceRevision={} current=PASS differentExpectedSha=STALE_SHA",
                ref, expectedSha, resolved.sourceRevision());
    }

    private String required(String name) {
        String value = environment.getProperty(name);
        assertTrue(value != null && !value.isBlank(), "Thiếu cấu hình thử nghiệm trong .env: " + name);
        return value.trim();
    }
}
