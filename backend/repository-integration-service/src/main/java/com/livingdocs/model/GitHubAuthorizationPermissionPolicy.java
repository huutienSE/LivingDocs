package com.livingdocs.model;

import com.livingdocs.exception.GitHubAuthorizationException;
import java.util.*;

/** Permission identity includes category; an unrelated grant can never satisfy a repository requirement. */
public final class GitHubAuthorizationPermissionPolicy {
    private static final Map<String,Integer> LEVEL = Map.of("read",1,"write",2,"admin",3);
    private static final Set<String> REPOSITORY = Set.of("actions","administration","attestations","checks",
            "code_scanning_alerts","codespaces","codespaces_lifecycle_admin","codespaces_metadata","codespaces_secrets",
            "contents","dependabot_secrets","dependabot_alerts","deployments","discussions","environments","issues",
            "merge_queues","metadata","packages","pages","pull_requests","repository_advisories",
            "repository_custom_properties","repository_hooks","repository_projects","secret_scanning_alerts",
            "secrets","security_events","single_file","statuses","vulnerability_alerts","workflows");
    private static final Set<String> ORGANIZATION = Set.of("members","organization_administration","organization_hooks",
            "organization_projects","organization_custom_properties","organization_secrets","organization_packages",
            "organization_plan","organization_user_blocking","team_discussions");
    private GitHubAuthorizationPermissionPolicy() {}

    public static GitHubAuthorizationVerification evaluate(GitHubAuthorizationInspection evidence,
            List<GitHubAuthorizationRequirements.Permission> required) {
        var granted = new ArrayList<GitHubAuthorizationVerification.Permission>();
        evidence.getPermissions().getPermissions().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String category = REPOSITORY.contains(entry.getKey()) ? "REPOSITORY"
                    : ORGANIZATION.contains(entry.getKey()) ? "ORGANIZATION" : null;
            if (category == null || !LEVEL.containsKey(entry.getValue()))
                throw new GitHubAuthorizationException(GitHubAuthorizationError.GITHUB_AUTHORIZATION_UNAVAILABLE);
            granted.add(new GitHubAuthorizationVerification.Permission(category, entry.getKey(), entry.getValue()));
        });
        var missing = required.stream().filter(wanted -> granted.stream().noneMatch(actual ->
                wanted.getCategory().equals(actual.getCategory()) && wanted.getName().equals(actual.getName())
                        && LEVEL.get(actual.getLevel()) >= LEVEL.get(wanted.getLevel())))
                .map(p -> new GitHubAuthorizationVerification.Permission(p.getCategory(),p.getName(),p.getLevel())).toList();
        String status = evidence.getPermissions().getInstallation().isSuspended() ? "INSTALLATION_SUSPENDED"
                : missing.isEmpty() ? "VERIFIED_SUFFICIENT" : "VERIFIED_INSUFFICIENT";
        return new GitHubAuthorizationVerification(status, evidence.getPermissions().getInstallation(),
                List.copyOf(granted), missing, evidence.getCheckedAt(), false);
    }
}
