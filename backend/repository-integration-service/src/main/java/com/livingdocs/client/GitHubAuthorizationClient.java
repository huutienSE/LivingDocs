package com.livingdocs.client;

import com.livingdocs.config.GitHubAuthorizationProperties;
import com.livingdocs.exception.GitHubAuthorizationException;
import com.livingdocs.model.GitHubAuthorizationError;
import com.livingdocs.model.GitHubUserAuthorization;
import com.livingdocs.model.GitHubAuthorizationDiscovery;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pinned GitHub origins only; no automatic redirects, retries, token refresh or provider-body logging. */
@Component
public class GitHubAuthorizationClient implements AutoCloseable {
    private final GitHubAuthorizationProperties properties;
    private final GitHubAuthorizationIo io;
    private final JsonMapper json;
    private final HttpClient transport;
    private final RestClient http;

    public GitHubAuthorizationClient(GitHubAuthorizationProperties properties, GitHubAuthorizationIo io, JsonMapper json) {
        this.properties = properties; this.io = io; this.json = json;
        transport = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(transport); factory.setReadTimeout(Duration.ofSeconds(2));
        http = RestClient.builder().requestFactory(factory).defaultHeader("User-Agent", "LivingDocs-S02")
                .defaultHeader("X-GitHub-Api-Version", "2026-03-10").build();
    }

    public GitHubUserAuthorization exchange(String code, String verifier, String correlation) {
        return exchange(properties.getClientId(), code, verifier, correlation);
    }

    public GitHubUserAuthorization exchange(String clientId, String code, String verifier, String correlation) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", clientId); form.add("client_secret", clientSecret());
        form.add("code", code); form.add("code_verifier", verifier); form.add("redirect_uri", properties.getCallbackUri());
        JsonNode token = read(http.post().uri(origin(false).resolve("/login/oauth/access_token"))
                .header("Accept", "application/json").header("X-Correlation-ID", correlation)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form), false);
        String error = token.path("error").asString("");
        if (!error.isEmpty()) {
            if (error.equals("bad_verification_code")) throw failure(GitHubAuthorizationError.GITHUB_REAUTHORIZATION_REQUIRED);
            if (java.util.Set.of("incorrect_client_credentials", "redirect_uri_mismatch").contains(error))
                throw failure(GitHubAuthorizationError.GITHUB_AUTHORIZATION_CONFIG_UNAVAILABLE);
            throw unavailable();
        }
        String accessToken = token.path("access_token").asString("");
        if (!accessToken.matches("ghu_[A-Za-z0-9_]{8,255}") || !"bearer".equals(token.path("token_type").asString(""))
                || !token.path("scope").isString() || !token.path("scope").asString().isEmpty()
                || !token.path("expires_in").isIntegralNumber()) throw unavailable();
        long seconds;
        try { seconds = Long.parseLong(token.path("expires_in").toString()); }
        catch (RuntimeException malformed) { throw unavailable(); }
        if (seconds <= 0 || seconds > 28800) throw unavailable();
        Instant expiry = Instant.now().plusSeconds(seconds).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        JsonNode user = read(http.get().uri(origin(true).resolve("/user"))
                .header("Accept", "application/vnd.github+json").header("X-Correlation-ID", correlation)
                .headers(h -> h.setBearerAuth(accessToken)), true);
        String userId = positiveId(user.path("id"));
        if (!user.path("login").isString() || user.path("login").asString().isBlank()
                || user.path("login").asString().length() > 100) throw unavailable();
        return new GitHubUserAuthorization(accessToken, userId, expiry);
    }

    private JsonNode read(RestClient.RequestHeadersSpec<?> request, boolean userToken) {
        return response(request, userToken).body;
    }

    private Response response(RestClient.RequestHeadersSpec<?> request, boolean userToken) {
        return io.call(Duration.ofSeconds(2), GitHubAuthorizationError.GITHUB_AUTHORIZATION_UNAVAILABLE, () -> {
            try {
                return request.exchange((out, in) -> {
                    int status = in.getStatusCode().value();
                    if (status == 401 && userToken) throw failure(GitHubAuthorizationError.GITHUB_REAUTHORIZATION_REQUIRED);
                    if (status != 200) throw unavailable();
                    var type = in.getHeaders().getContentType();
                    if (type == null || !"application".equals(type.getType())
                            || !("json".equals(type.getSubtype()) || type.getSubtype().endsWith("+json"))) throw unavailable();
                    byte[] bytes = in.getBody().readNBytes(1024 * 1024 + 1);
                    if (bytes.length > 1024 * 1024) throw unavailable();
                    JsonNode result = json.readTree(bytes);
                    if (result == null || !result.isObject()) throw unavailable();
                    var links = in.getHeaders().get("Link");
                    String link = links == null ? null : String.join(",", links);
                    if (link != null && link.length() > 2048) throw unavailable();
                    return new Response(result, link);
                });
            } catch (GitHubAuthorizationException expected) { throw expected; }
            catch (RuntimeException failed) { throw unavailable(); }
        });
    }

    public GitHubAuthorizationDiscovery.Page<GitHubAuthorizationDiscovery.Installation> installations(String token, int page, String correlation) {
        return installations(token, properties.getAppId(), page, correlation);
    }

    public GitHubAuthorizationDiscovery.Page<GitHubAuthorizationDiscovery.Installation> installations(String token, String expectedApp, int page, String correlation) {
        String path = "/user/installations";
        Response response = userPage(path, token, page, correlation);
        JsonNode items = pageItems(response.body, "installations");
        var result = new java.util.ArrayList<GitHubAuthorizationDiscovery.Installation>();
        var seen = new java.util.HashSet<String>();
        try {
            for (JsonNode item : items) {
                String id = positiveId(item.path("id"));
                if (!seen.add(id) || !expectedApp.equals(positiveId(item.path("app_id")))) throw unavailable();
                String accountId = positiveId(item.path("account").path("id"));
                String login = text(item.path("account").path("login"), 100);
                String selection = item.path("repository_selection").asString("");
                if (!java.util.Set.of("all", "selected").contains(selection)) throw unavailable();
                JsonNode suspended = item.path("suspended_at");
                if (!suspended.isNull()) Instant.parse(text(suspended, 100));
                result.add(new GitHubAuthorizationDiscovery.Installation(id, accountId, login, selection, !suspended.isNull()));
            }
            return new GitHubAuthorizationDiscovery.Page<>(result, page, hasNext(response, path, page));
        } catch (GitHubAuthorizationException expected) { throw expected; }
        catch (RuntimeException malformed) { throw unavailable(); }
    }

    public GitHubAuthorizationDiscovery.Page<GitHubAuthorizationDiscovery.Repository> repositories(String token, String installation, int page, String correlation) {
        validateInstallationId(installation);
        String path = "/user/installations/" + installation + "/repositories";
        Response response = userPage(path, token, page, correlation);
        JsonNode items = pageItems(response.body, "repositories");
        var result = new java.util.ArrayList<GitHubAuthorizationDiscovery.Repository>();
        var seen = new java.util.HashSet<String>();
        for (JsonNode item : items) {
            String id = positiveId(item.path("id"));
            String name = text(item.path("full_name"), 300);
            if (!seen.add(id) || name.length() < 3 || name.split("/", -1).length != 2
                    || name.startsWith("/") || name.endsWith("/")) throw unavailable();
            JsonNode pull = item.path("permissions").path("pull");
            if (!pull.isBoolean()) throw unavailable();
            if (pull.asBoolean()) result.add(new GitHubAuthorizationDiscovery.Repository(id, name, true));
        }
        return new GitHubAuthorizationDiscovery.Page<>(result, page, hasNext(response, path, page));
    }

    private Response userPage(String path, String token, int page, String correlation) {
        if (page <= 0) throw failure(GitHubAuthorizationError.INVALID_REQUEST);
        return response(http.get().uri(origin(true).resolve(path + "?per_page=100&page=" + page))
                .headers(h -> h.setBearerAuth(token)).header("Accept", "application/vnd.github+json")
                .header("X-Correlation-ID", correlation), true);
    }

    public static void validateInstallationId(String installation) {
        try {
            if (installation == null || !installation.matches("[1-9][0-9]{0,18}") || Long.parseLong(installation) <= 0)
                throw new IllegalArgumentException();
        } catch (RuntimeException malformed) { throw failure(GitHubAuthorizationError.INVALID_REQUEST); }
    }

    private static JsonNode pageItems(JsonNode body, String field) {
        try {
            JsonNode items = body.path(field);
            if (!items.isArray() || items.size() > 100 || !body.path("total_count").isIntegralNumber()
                    || Long.parseLong(body.path("total_count").toString()) < items.size()) throw unavailable();
            return items;
        } catch (RuntimeException malformed) { throw unavailable(); }
    }

    public com.livingdocs.model.GitHubAppProfile application(String appJwt, String correlation) {
        JsonNode body = appRead("/app", appJwt, correlation);
        String slug = text(body.path("slug"), 100), clientId = text(body.path("client_id"), 128);
        if (!slug.matches("[a-z0-9][a-z0-9-]{0,99}") || !clientId.matches("[A-Za-z0-9_.-]{6,128}")) throw unavailable();
        var events = new java.util.HashSet<String>();
        if (!body.path("events").isArray() || body.path("events").size() > 100) throw unavailable();
        for (JsonNode event : body.path("events")) {
            String name = text(event, 64);
            if (!name.matches("[a-z][a-z0-9_]{0,63}") || !events.add(name)) throw unavailable();
        }
        return new com.livingdocs.model.GitHubAppProfile(positiveId(body.path("id")), slug, text(body.path("name"),200),
                clientId, permissions(body.path("permissions")), events);
    }

    public com.livingdocs.model.GitHubInstallationPermissions installation(String appJwt, com.livingdocs.model.GitHubAppProfile app,
            String installationId, String correlation) {
        validateInstallationId(installationId);
        JsonNode body = appRead("/app/installations/" + installationId, appJwt, correlation);
        if (!installationId.equals(positiveId(body.path("id"))) || !app.getAppId().equals(positiveId(body.path("app_id")))
                || !app.getSlug().equals(text(body.path("app_slug"),100))) throw unavailable();
        String selection = text(body.path("repository_selection"),16);
        if (!java.util.Set.of("all","selected").contains(selection)) throw unavailable();
        JsonNode suspended = body.path("suspended_at");
        if (!suspended.isNull()) {
            try { Instant.parse(text(suspended,100)); } catch (RuntimeException malformed) { throw unavailable(); }
        }
        var installation = new GitHubAuthorizationDiscovery.Installation(installationId, positiveId(body.path("account").path("id")),
                text(body.path("account").path("login"),100), selection, !suspended.isNull());
        return new com.livingdocs.model.GitHubInstallationPermissions(installation, permissions(body.path("permissions")));
    }

    private JsonNode appRead(String path, String jwt, String correlation) {
        return read(http.get().uri(origin(true).resolve(path)).headers(h -> h.setBearerAuth(jwt))
                .header("Accept","application/vnd.github+json").header("X-Correlation-ID",correlation),false);
    }

    private static java.util.Map<String,String> permissions(JsonNode node) {
        if (!node.isObject() || node.size() > 128) throw unavailable();
        var result = new java.util.HashMap<String,String>();
        for (var entry : node.properties()) {
            if (!entry.getKey().matches("[a-z][a-z0-9_]{0,63}") || !entry.getValue().isString()
                    || !java.util.Set.of("read","write","admin").contains(entry.getValue().asString())) throw unavailable();
            result.put(entry.getKey(),entry.getValue().asString());
        }
        return java.util.Map.copyOf(result);
    }

    private boolean hasNext(Response response, String path, int page) {
        String links = response.link;
        boolean hasMore = Long.parseLong(response.body.path("total_count").toString()) > (long)page * 100;
        if (links == null || links.isBlank()) { if (hasMore) throw unavailable(); return false; }
        var matcher = java.util.regex.Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"").matcher(links);
        if (!matcher.find()) { if (hasMore) throw unavailable(); return false; }
        try {
            URI next = URI.create(matcher.group(1)), base = origin(true);
            if (!hasMore || matcher.find() || page == Integer.MAX_VALUE || !base.getScheme().equals(next.getScheme())
                    || !base.getHost().equalsIgnoreCase(next.getHost()) || base.getPort() != next.getPort()
                    || next.getUserInfo() != null || next.getFragment() != null || !path.equals(next.getPath())
                    || next.getRawQuery() == null) throw unavailable();
            var parameters = new java.util.HashMap<String, String>();
            for (String pair : next.getRawQuery().split("&")) {
                String[] parts = pair.split("=", -1);
                if (parts.length != 2 || parameters.putIfAbsent(parts[0], parts[1]) != null) throw unavailable();
            }
            if (!parameters.equals(java.util.Map.of("per_page", "100", "page", Integer.toString(page + 1)))) throw unavailable();
            return true;
        } catch (GitHubAuthorizationException expected) { throw expected; }
        catch (RuntimeException malformed) { throw unavailable(); }
    }

    private static String text(JsonNode node, int max) {
        if (!node.isString()) throw unavailable();
        String text = node.asString();
        if (text.isBlank() || text.length() > max || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) throw unavailable();
        return text;
    }

    private static final class Response {
        private final JsonNode body;
        private final String link;
        private Response(JsonNode body, String link) { this.body = body; this.link = link; }
    }

    private URI origin(boolean api) {
        try {
            URI base = properties.trustedUri(api ? properties.getApiBaseUri() : properties.getOauthBaseUri());
            boolean fixture = properties.isAllowLocalHttp() && "http".equals(base.getScheme())
                    && java.util.Set.of("127.0.0.1", "localhost").contains(base.getHost());
            if ((!fixture && !base.toString().equals(api ? "https://api.github.com" : "https://github.com"))
                    || !(base.getPath().isEmpty() || "/".equals(base.getPath()))) throw new IllegalArgumentException();
            return base;
        } catch (RuntimeException failed) { throw failure(GitHubAuthorizationError.GITHUB_AUTHORIZATION_CONFIG_UNAVAILABLE); }
    }

    private String clientSecret() {
        try {
            if (properties.getClientSecretPath().isBlank()) throw new IllegalArgumentException();
            byte[] bytes;
            try (var input = Files.newInputStream(Path.of(properties.getClientSecretPath()))) { bytes = input.readNBytes(257); }
            String value = new String(bytes, StandardCharsets.US_ASCII).strip();
            if (bytes.length > 256 || !value.matches("[A-Za-z0-9_-]{8,255}")) throw new IllegalArgumentException();
            return value;
        } catch (Exception failed) { throw failure(GitHubAuthorizationError.GITHUB_AUTHORIZATION_CONFIG_UNAVAILABLE); }
    }

    private static String positiveId(JsonNode node) {
        try {
            if (!node.isIntegralNumber()) throw unavailable();
            long value = Long.parseLong(node.toString());
            if (value <= 0) throw unavailable();
            return Long.toString(value);
        } catch (RuntimeException malformed) { throw unavailable(); }
    }
    private static GitHubAuthorizationException unavailable() { return failure(GitHubAuthorizationError.GITHUB_AUTHORIZATION_UNAVAILABLE); }
    private static GitHubAuthorizationException failure(GitHubAuthorizationError error) { return new GitHubAuthorizationException(error); }
    @Override public void close() { transport.shutdownNow(); }
}
