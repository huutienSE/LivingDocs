package com.livingdocs.client;

import com.livingdocs.config.GitHubAppProperties;
import com.livingdocs.config.GitHubAuthorizationProperties;
import com.livingdocs.exception.GitHubAuthorizationException;
import com.livingdocs.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Only verified App registration metadata is cached; user grants/installations never enter this cache. */
@Component @RequiredArgsConstructor
public class GitHubAuthorizationAppCatalog {
    private static final Map<String,String> REQUIRED = Map.of("metadata","read","contents","write","pull_requests","write","checks","write");
    private final GitHubAuthorizationClient github;
    private final GitHubAppJwtSigner signer;
    private final GitHubAppProperties app;
    private final GitHubAuthorizationProperties config;
    private final ReentrantLock refresh = new ReentrantLock();
    private volatile Cached cached;

    public String configuredClientId() { return config.getClientId().isBlank() ? Objects.toString(app.getClientId(), "") : config.getClientId(); }

    public GitHubAppProfile read(String correlation) {
        GitHubAuthorizationDeadline.check();
        String key = String.join("\n", configuredClientId(), Objects.toString(app.getClientId(),""), Objects.toString(app.getKeyDirectory(),""),
                config.getAppKeyReference(), config.getAppId(), config.getAppSlug(), config.getAppName(), config.getApiBaseUri());
        Cached entry = cached;
        if (entry != null && entry.key.equals(key) && System.nanoTime() < entry.until) return entry.profile;
        if (!refresh.tryLock()) throw new GitHubAuthorizationException(GitHubAuthorizationError.GITHUB_AUTHORIZATION_UNAVAILABLE);
        try {
            entry = cached;
            if (entry != null && entry.key.equals(key) && System.nanoTime() < entry.until) return entry.profile;
            var profile = github.application(signer.sign(keyReference()), correlation);
            String source = Objects.toString(app.getClientId(), ""), configured = configuredClientId();
            if (!(source.equals(profile.getClientId()) || source.equals(profile.getAppId()))
                    || !(configured.equals(profile.getClientId()) || (configured.equals(source) && source.equals(profile.getAppId())))
                    || (!config.getAppId().isBlank() && !config.getAppId().equals(profile.getAppId()))
                    || (!config.getAppSlug().isBlank() && !config.getAppSlug().equals(profile.getSlug()))
                    || (!config.getAppName().isBlank() && !config.getAppName().equals(profile.getName()))
                    || !REQUIRED.equals(profile.getPermissions()) || !Set.of("push","pull_request").equals(profile.getEvents())) throw unavailable();
            cached = new Cached(key, profile, System.nanoTime() + Duration.ofSeconds(30).toNanos());
            return profile;
        } catch (GitHubAuthorizationException expected) { throw expected; }
        catch (RuntimeException invalid) { throw unavailable(); }
        finally { refresh.unlock(); }
    }

    public GitHubInstallationPermissions installation(GitHubAppProfile profile, GitHubAuthorizationDiscovery.Installation proof, String correlation) {
        // Caller must supply a fresh user-scoped proof before this method can issue an App-authenticated probe.
        try {
            var metadata = github.installation(signer.sign(keyReference()), profile, proof.getInstallationId(), correlation);
            if (!metadata.getInstallation().getAccountId().equals(proof.getAccountId()))
                throw new GitHubAuthorizationException(GitHubAuthorizationError.GITHUB_AUTHORIZATION_UNAVAILABLE);
            return metadata;
        } catch (GitHubAuthorizationException expected) { throw expected; }
        catch (RuntimeException invalid) { throw unavailable(); }
    }

    private String keyReference() {
        if (!config.getAppKeyReference().isBlank()) return config.getAppKeyReference();
        if (app.getKeyDirectory() == null || app.getKeyDirectory().isBlank()) throw unavailable();
        try (var files = Files.list(Path.of(Objects.toString(app.getKeyDirectory(), "")))) {
            var candidates = files.limit(128).filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") && n.toLowerCase(Locale.ROOT).endsWith(".pem")).limit(2).toList();
            if (candidates.size() != 1) throw unavailable();
            return candidates.getFirst();
        } catch (java.io.IOException | RuntimeException invalid) { throw unavailable(); }
    }
    private static GitHubAuthorizationException unavailable() { return new GitHubAuthorizationException(GitHubAuthorizationError.GITHUB_AUTHORIZATION_CONFIG_UNAVAILABLE); }
    private static final class Cached {
        private final String key; private final GitHubAppProfile profile; private final long until;
        private Cached(String key, GitHubAppProfile profile, long until) { this.key=key; this.profile=profile; this.until=until; }
    }
}
