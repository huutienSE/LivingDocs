package com.livingdocs.document;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import com.livingdocs.document.service.DocumentReviewService;
import com.livingdocs.document.model.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class DocumentGovernanceServiceApplicationTests extends PostgresTestSupport {
    @Autowired DocumentReviewService reviews;

	@Test
	void contextLoads() {
	}

    @Test
    void noSourceAdapter_shouldFailClosedWithNoDatabaseChanges() throws Exception {
        var f = ReviewApiIntegrationTest.fixture();
        assertThatThrownBy(() -> reviews.review(f.version(), new StaffDecisionCommand(ReviewAction.PASS, null, List.of(f.evidence())),
                0, "no-source-adapter", f.context(), "no-adapter"))
                .isInstanceOfSatisfying(com.livingdocs.document.exception.SourceValidationUnavailableException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.getErrorCode()).isEqualTo(com.livingdocs.document.exception.ReviewErrorCode.SOURCE_VALIDATION_UNAVAILABLE);
                });
        assertThat(ReviewApiIntegrationTest.scalar("SELECT revision FROM document.document_versions WHERE document_version_id=?", f.version())).isEqualTo(0L);
        assertThat(ReviewApiIntegrationTest.scalar("SELECT review_stage FROM document.document_versions WHERE document_version_id=?", f.version())).isEqualTo("STAFF_REVIEW");
        assertThat(ReviewApiIntegrationTest.scalar("SELECT count(*) FROM document.review_decisions WHERE document_version_id=?", f.version())).isEqualTo(0L);
        assertThat(ReviewApiIntegrationTest.scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?", f.version())).isEqualTo(0L);
    }

}
