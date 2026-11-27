package com.livingdocs.client;

import com.livingdocs.config.GitHubAuthorizationProperties;
import com.livingdocs.exception.GitHubAuthorizationException;
import com.livingdocs.model.GitHubAuthorizationError;
import com.livingdocs.model.GitHubAuthorizationUser;
import com.livingdocs.model.S01AccessSnapshot;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads only the authenticated actor's self-context. Never accesses the S01 schema. */
@Component
public class S01AccessContextClient implements AutoCloseable {
    private final GitHubAuthorizationProperties config;
    private final JsonMapper json;
    private final GitHubAuthorizationIo io;
    private final HttpClient transport;
    private final RestClient client;
    public S01AccessContextClient(GitHubAuthorizationProperties config, JsonMapper json, GitHubAuthorizationIo io) {
        this.config = config;
        this.json = json;
        this.io = io;
        this.transport = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofMillis(1500));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public S01AccessSnapshot read(GitHubAuthorizationUser user, String correlation) {
        return io.call(Duration.ofMillis(1500), GitHubAuthorizationError.AUTHORIZATION_CONTEXT_UNAVAILABLE,
                () -> load(user, correlation));
    }

    private S01AccessSnapshot load(GitHubAuthorizationUser user, String correlation) {
        try {
            var base = config.trustedUri(config.getS01BaseUri());
            if (!base.getPath().isEmpty() && !"/".equals(base.getPath())) throw unavailable();
            JsonNode result = client.get().uri(base.resolve("/api/v1/users/me/access-context"))
                    .headers(h -> { h.setBearerAuth(user.getBearerToken()); h.set("X-Correlation-ID",correlation); })
                    .exchange((out, in) -> {
                        int status = in.getStatusCode().value();
                        if (status==401) throw new GitHubAuthorizationException(GitHubAuthorizationError.UNAUTHENTICATED);
                        var media = in.getHeaders().getContentType();
                        if (media == null || !(status == 200
                                ? org.springframework.http.MediaType.APPLICATION_JSON.isCompatibleWith(media)
                                : org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(media))) throw unavailable();
                        byte[] bytes = in.getBody().readNBytes(256*1024+1);
                        if (bytes.length > 256*1024) throw unavailable();
                        JsonNode node = json.readTree(bytes);
                        if (status==403) {
                            if (node!=null && Set.of("IDENTITY_INACTIVE","TENANT_INACTIVE","TENANT_MEMBERSHIP_REQUIRED","AUTHZ_DENIED")
                                    .contains(node.path("code").asString(""))) throw new GitHubAuthorizationException(GitHubAuthorizationError.FORBIDDEN_SCOPE);
                            throw unavailable();
                        }
                        if (status!=200 || node==null) throw unavailable();
                        return node;
                    });
            if (!"ACCESS_CONTEXT_LOADED".equals(result.path("code").asString(""))
                    || !result.path("message").isString()) throw unavailable();
            JsonNode data = result.path("data");
            if (!user.getIdentityId().toString().equals(data.path("identityId").asString(""))
                    || !data.path("authzRevision").isIntegralNumber()
                    || Long.parseLong(data.path("authzRevision").toString()) < 0) throw unavailable();
            Instant evaluated = Instant.parse(data.path("evaluatedAt").asString(""));
            if (Duration.between(evaluated,Instant.now()).abs().compareTo(Duration.ofSeconds(30))>0) throw unavailable();
            JsonNode tenant = data.path("tenantId");
            UUID tenantId = tenant.isNull() ? null : uuid(tenant);
            strings(data.path("tenantRoles"));
            strings(data.path("tenantPermissions"));
            JsonNode grants = data.path("workspaceGrants");
            if (!grants.isArray()) throw unavailable();
            Map<UUID, S01AccessSnapshot.WorkspaceGrant> workspaceGrants = new HashMap<>();
            for (JsonNode grant : grants) {
                UUID id = uuid(grant.path("workspaceId"));
                Set<String> roles = strings(grant.path("roles"));
                Set<String> permissions = strings(grant.path("permissions"));
                JsonNode repositories = grant.path("repositoryIds");
                if (!repositories.isArray()) throw unavailable();
                for (JsonNode repository : repositories) uuid(repository);
                if (workspaceGrants.putIfAbsent(id, new S01AccessSnapshot.WorkspaceGrant(roles, permissions)) != null)
                    throw unavailable();
            }
            return new S01AccessSnapshot(tenantId, workspaceGrants);
        } catch (GitHubAuthorizationException e) { throw e; }
        catch (RuntimeException e) { throw unavailable(); }
    }
    private static UUID uuid(JsonNode node) {
        if (!node.isString()) throw unavailable();
        String value = node.asString();
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value) || id.equals(new UUID(0, 0))) throw unavailable();
        return id;
    }
    private static Set<String> strings(JsonNode node) {
        if (!node.isArray()) throw unavailable();
        Set<String> result = new HashSet<>();
        for (JsonNode item : node) { if (!item.isString()) throw unavailable(); result.add(item.asString()); }
        return result;
    }
    private static GitHubAuthorizationException unavailable() { return new GitHubAuthorizationException(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_UNAVAILABLE); }

    @Override
    public void close() { transport.shutdownNow(); }
}
