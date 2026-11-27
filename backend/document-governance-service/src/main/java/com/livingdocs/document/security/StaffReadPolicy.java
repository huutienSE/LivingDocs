package com.livingdocs.document.security;

import com.livingdocs.document.model.S01AccessContext;
import java.util.List;
import java.util.UUID;

/** Staff-only read slice; mutation permissions do not grant or gate reads. */
public final class StaffReadPolicy {
    private StaffReadPolicy() {}

    public static List<S01AccessContext.Grant> grants(S01AccessContext actor) {
        return actor.grants().stream().filter(g -> g.roles().contains("STAFF")
                && g.permissions().containsAll(List.of("DOCUMENT_READ", "WORKSPACE_VIEW"))).toList();
    }

    public static boolean permits(List<S01AccessContext.Grant> grants, UUID workspace, UUID repository) {
        return grants.stream().anyMatch(g -> g.workspaceId().equals(workspace)
                && (repository == null || g.repositoryIds().contains(repository)));
    }
}
