package com.livingdocs.document;

import com.livingdocs.document.model.S01AccessContext;
import com.livingdocs.document.security.StaffReadPolicy;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StaffReadPolicyTest {
    @Test void permissionsAreReadsAndMustBelongToTheStaffWorkspaceGrant() {
        UUID workspace = UUID.randomUUID();
        for (var grant : List.of(
                new S01AccessContext.Grant(workspace, Set.of("STAFF"), Set.of("DOCUMENT_STAFF_REVIEW"), Set.of()),
                new S01AccessContext.Grant(workspace, Set.of("MANAGER"), Set.of("DOCUMENT_READ", "WORKSPACE_VIEW"), Set.of()),
                new S01AccessContext.Grant(workspace, Set.of("STAFF"), Set.of("DOCUMENT_READ"), Set.of()))) {
            var actor = new S01AccessContext(UUID.randomUUID(), UUID.randomUUID(), 1, Instant.now(), List.of(grant));
            assertThat(StaffReadPolicy.grants(actor)).isEmpty();
        }
    }

    @Test void repositoryIdsCannotCrossMultiplyAcrossWorkspaceGrants() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), repoA = UUID.randomUUID(), repoB = UUID.randomUUID();
        var grants = List.of(
                new S01AccessContext.Grant(a, Set.of("STAFF"), Set.of("DOCUMENT_READ", "WORKSPACE_VIEW"), Set.of(repoA)),
                new S01AccessContext.Grant(b, Set.of("STAFF"), Set.of("DOCUMENT_READ", "WORKSPACE_VIEW"), Set.of(repoB)));
        assertThat(StaffReadPolicy.permits(grants, a, repoA)).isTrue();
        assertThat(StaffReadPolicy.permits(grants, a, repoB)).isFalse();
        assertThat(StaffReadPolicy.permits(grants, b, repoA)).isFalse();
        assertThat(StaffReadPolicy.permits(grants, a, null)).isTrue();
        assertThat(StaffReadPolicy.permits(grants, UUID.randomUUID(), null)).isFalse();
    }
}
