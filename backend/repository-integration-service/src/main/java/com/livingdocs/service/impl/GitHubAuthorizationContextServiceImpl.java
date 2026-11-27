package com.livingdocs.service.impl;

import com.livingdocs.client.S01AccessContextClient;
import com.livingdocs.client.GitHubAuthorizationClient;
import com.livingdocs.config.GitHubAuthorizationProperties;
import com.livingdocs.entity.GitHubAuthorizationContextEntity;
import com.livingdocs.exception.GitHubAuthorizationException;
import com.livingdocs.model.GitHubAuthorizationContextBinding;
import com.livingdocs.model.GitHubAuthorizationContextSnapshot;
import com.livingdocs.model.GitHubAuthorizationContextStart;
import com.livingdocs.model.GitHubAuthorizationError;
import com.livingdocs.model.GitHubAuthorizationPolicy;
import com.livingdocs.model.GitHubAuthorizationUser;
import com.livingdocs.model.GitHubAuthorizationCallbackResult;
import com.livingdocs.model.GitHubAuthorizationDiscovery;
import com.livingdocs.repository.GitHubAuthorizationContextRepository;
import com.livingdocs.security.GitHubAuthorizationContextCodec;
import com.livingdocs.service.GitHubAuthorizationContextService;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** S01/provider I/O is always outside the short, tenant-scoped database transaction. */
@Service @RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
public class GitHubAuthorizationContextServiceImpl implements GitHubAuthorizationContextService {
    private final GitHubAuthorizationProperties properties;
    private final S01AccessContextClient authority;
    private final GitHubAuthorizationContextCodec codec;
    private final GitHubAuthorizationClient github;
    private final com.livingdocs.client.GitHubAuthorizationAppCatalog catalog;
    private final GitHubAuthorizationContextRepository contexts;
    private final EntityManager entities;
    private final PlatformTransactionManager transactionManager;

    @Override
    public GitHubAuthorizationContextStart start(GitHubAuthorizationUser actor, UUID workspace, String origin, String correlation) {
        var access = authority.read(actor, correlation);
        UUID tenant = GitHubAuthorizationPolicy.workspace(access, workspace);
        var profile = catalog.read(correlation);
        String configuration = configurationVersion(profile.getAppId());
        if (!Objects.equals(origin, properties.getBrowserOrigin())) throw failure(GitHubAuthorizationError.FORBIDDEN_SCOPE);
        Instant expires = Instant.now().plus(properties.getContextTtl()).truncatedTo(ChronoUnit.SECONDS);
        var binding = new GitHubAuthorizationContextBinding(UUID.randomUUID(), tenant, workspace, actor.getIdentityId(),
                profile.getAppId(), configuration, expires);
        var prepared = codec.prepare(binding);
        inTransaction(tenant, false, () -> {
            entities.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:scope, 0))")
                    .setParameter("scope", tenant + ":" + actor.getIdentityId() + ":" + workspace).getSingleResult();
            if (contexts.countActive(tenant, workspace, actor.getIdentityId()) >= 3)
                throw new GitHubAuthorizationException(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_LIMIT_REACHED,
                        contexts.secondsUntilNextSlot(tenant, workspace, actor.getIdentityId()));
            contexts.saveAndFlush(new GitHubAuthorizationContextEntity(binding, GitHubAuthorizationContextCodec.digest(prepared.getState()),
                    GitHubAuthorizationContextCodec.digest(prepared.getCookieNonce()), prepared.getCredentialCiphertext(),
                    prepared.getStateKeyVersion(), prepared.getCredentialKeyVersion()));
            return null;
        });
        String authorization = "https://github.com/login/oauth/authorize?client_id=" + query(profile.getClientId())
                + "&redirect_uri=" + query(properties.getCallbackUri()) + "&state=" + query(prepared.getState())
                + "&code_challenge=" + prepared.getChallenge() + "&code_challenge_method=S256";
        return new GitHubAuthorizationContextStart(binding.getContextId(), expires, authorization,
                "https://github.com/apps/" + profile.getSlug() + "/installations/new", prepared.getCookieNonce());
    }

    @Override
    public GitHubAuthorizationContextSnapshot inspect(GitHubAuthorizationUser actor, UUID workspace, UUID contextId, String correlation) {
        var access = authority.read(actor, correlation);
        UUID tenant = GitHubAuthorizationPolicy.workspace(access, workspace);
        var row = inTransaction(tenant, true, () -> {
            var context = owned(tenant, actor.getIdentityId(), contextId);
            if (!workspace.equals(context.getWorkspaceId())) throw failure(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_NOT_FOUND);
            if (!Instant.now().isBefore(context.getExpiresAt())) throw failure(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_EXPIRED);
            if (!configurationVersion(context.getAppId()).equals(context.getAppConfigVersion())) throw failure(GitHubAuthorizationError.AUTHORIZATION_CONFIGURATION_CHANGED);
            if (!java.util.Set.of("PENDING", "EXCHANGING", "AUTHORIZED").contains(context.getStatus()))
                throw failure(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_CONSUMED);
            return context;
        });
        if ("AUTHORIZED".equals(row.getStatus())) authorization(row);
        return new GitHubAuthorizationContextSnapshot(contextId, row.getExpiresAt(),
                "AUTHORIZED".equals(row.getStatus()) ? "AUTHORIZED" : "PENDING");
    }

    private com.livingdocs.model.GitHubUserAuthorization authorization(GitHubAuthorizationContextEntity row) {
        var payload = codec.openCredential(row.binding(), row.getCredentialKeyVersion(), row.getSecretPayload());
        String token = payload.path("accessToken").asString("");
        String user = payload.path("githubUserId").asString("");
        try {
            Instant expires = Instant.ofEpochSecond(Long.parseLong(payload.path("expiresAt").toString()));
            if (!token.matches("ghu_[A-Za-z0-9_]{8,255}") || !user.equals(row.getGithubUserId())
                    || row.getTokenExpiresAt() == null || expires.getEpochSecond() != row.getTokenExpiresAt().getEpochSecond())
                throw failure(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_UNAVAILABLE);
            if (!Instant.now().isBefore(expires)) throw failure(GitHubAuthorizationError.GITHUB_REAUTHORIZATION_REQUIRED);
            return new com.livingdocs.model.GitHubUserAuthorization(token, user,
                    row.getExpiresAt().isBefore(expires) ? row.getExpiresAt() : expires);
        } catch (GitHubAuthorizationException expected) { throw expected; }
        catch (RuntimeException failed) { throw failure(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_UNAVAILABLE); }
    }

    @Override
    public void cancel(GitHubAuthorizationUser actor, UUID contextId, String correlation) {
        var access = authority.read(actor, correlation);
        UUID tenant = GitHubAuthorizationPolicy.tenant(access);
        inTransaction(tenant, false, () -> {
            var context = owned(tenant, actor.getIdentityId(), contextId);
            GitHubAuthorizationPolicy.workspace(access, context.getWorkspaceId());
            contexts.cancel(tenant, actor.getIdentityId(), contextId);
            return null;
        });
    }

    @Override
    public GitHubAuthorizationCallbackResult complete(String state, java.util.Map<String, String> cookies,
                                                     String code, String error, String correlation) {
        if ((code == null) == (error == null) || (code != null && !code.matches("[\\x21-\\x7e]{1,2048}"))
                || (error != null && !error.matches("[a-z_]{1,64}"))) throw failure(GitHubAuthorizationError.OAUTH_STATE_INVALID);
        var verified = codec.verifyState(state, cookies);
        var binding = verified.getBinding();
        if (!configurationVersion(binding.getAppId()).equals(binding.getAppConfigVersion())) throw failure(GitHubAuthorizationError.AUTHORIZATION_CONFIGURATION_CHANGED);
        var claimed = inTransaction(binding.getTenantId(), false, () -> {
            var row = owned(binding.getTenantId(), binding.getActorIdentityId(), binding.getContextId());
            if (!row.getWorkspaceId().equals(binding.getWorkspaceId()) || !row.getAppId().equals(binding.getAppId())
                    || !row.getAppConfigVersion().equals(binding.getAppConfigVersion()) || !row.getExpiresAt().equals(binding.getExpiresAt())
                    || !row.getStateDigest().equals(GitHubAuthorizationContextCodec.digest(state))
                    || !row.getBrowserNonceDigest().equals(verified.getNonceDigest())
                    || !row.getStateKeyVersion().equals(verified.getStateKeyVersion())) throw failure(GitHubAuthorizationError.OAUTH_STATE_INVALID);
            if (!"PENDING".equals(row.getStatus())) throw failure(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_CONSUMED);
            if (contexts.claim(binding, GitHubAuthorizationContextCodec.digest(state), verified.getNonceDigest(), verified.getStateKeyVersion()) != 1)
                throw failure(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_CONSUMED);
            return owned(binding.getTenantId(), binding.getActorIdentityId(), binding.getContextId());
        });
        if (error != null) {
            failClaim(claimed);
            return completion(binding.getContextId());
        }
        try {
            String verifier = codec.openCredential(binding, claimed.getCredentialKeyVersion(), claimed.getSecretPayload())
                    .path("verifier").asString("");
            if (!verifier.matches("[A-Za-z0-9_-]{43}")) throw failure(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_UNAVAILABLE);
            var profile = catalog.read(correlation);
            if (!profile.getAppId().equals(binding.getAppId())) throw failure(GitHubAuthorizationError.AUTHORIZATION_CONFIGURATION_CHANGED);
            var authorization = github.exchange(profile.getClientId(), code, verifier, correlation);
            if (!configurationVersion(binding.getAppId()).equals(binding.getAppConfigVersion())) throw failure(GitHubAuthorizationError.AUTHORIZATION_CONFIGURATION_CHANGED);
            if (!Instant.now().isBefore(authorization.getExpiresAt())) throw failure(GitHubAuthorizationError.GITHUB_REAUTHORIZATION_REQUIRED);
            String payload = codec.sealCredential(binding, claimed.getCredentialKeyVersion(), java.util.Map.of(
                    "accessToken", authorization.getAccessToken(), "githubUserId", authorization.getGithubUserId(),
                    "expiresAt", authorization.getExpiresAt().getEpochSecond()));
            inTransaction(binding.getTenantId(), false, () -> {
                if (contexts.complete(binding, claimed.getRowRevision(), payload, authorization.getGithubUserId(), authorization.getExpiresAt()) != 1) {
                    var latest = owned(binding.getTenantId(), binding.getActorIdentityId(), binding.getContextId());
                    throw failure(!Instant.now().isBefore(latest.getExpiresAt()) || "EXPIRED".equals(latest.getStatus())
                            ? GitHubAuthorizationError.AUTHORIZATION_CONTEXT_EXPIRED : GitHubAuthorizationError.AUTHORIZATION_CONTEXT_CONSUMED);
                }
                return null;
            });
            return completion(binding.getContextId());
        } catch (RuntimeException failed) {
            try { failClaim(claimed); } catch (RuntimeException ignored) { /* Expiry maintenance remains responsible for purging secrets. */ }
            if (failed instanceof GitHubAuthorizationException expected) throw expected;
            throw failure(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_UNAVAILABLE);
        }
    }

    private void failClaim(GitHubAuthorizationContextEntity claimed) {
        // Only terminal purge may continue after the caller's deadline; it can never authorize or resurrect a context.
        boolean interrupted = Thread.interrupted();
        try (var ignored = com.livingdocs.model.GitHubAuthorizationDeadline.bind(null)) {
            inTransaction(claimed.getTenantId(), false, () -> contexts.failClaim(claimed.binding(), claimed.getRowRevision()));
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private GitHubAuthorizationCallbackResult completion(UUID contextId) {
        return new GitHubAuthorizationCallbackResult(contextId, properties.getCompletionUri() + "?contextId=" + contextId);
    }

    @Override
    public int purgeExpired() {
        return inTransaction(null, false, () -> ((Number) entities.createNativeQuery(
                "select repository.purge_expired_github_authorization_contexts()").getSingleResult()).intValue());
    }

    @Override
    public GitHubAuthorizationDiscovery.Page<GitHubAuthorizationDiscovery.Installation> installations(
            GitHubAuthorizationUser actor, UUID contextId, int page, String correlation) {
        if (page <= 0) throw failure(GitHubAuthorizationError.INVALID_REQUEST);
        var row = discoveryContext(actor, null, contextId, correlation);
        var credential = authorization(row);
        var result = github.installations(credential.getAccessToken(), row.getAppId(), page, correlation);
        if (!Instant.now().isBefore(credential.getExpiresAt())) throw failure(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_EXPIRED);
        return result;
    }

    @Override
    public GitHubAuthorizationDiscovery.Page<GitHubAuthorizationDiscovery.Repository> repositories(
            GitHubAuthorizationUser actor, UUID contextId, String installationId, int page, String correlation) {
        if (page <= 0) throw failure(GitHubAuthorizationError.INVALID_REQUEST);
        GitHubAuthorizationClient.validateInstallationId(installationId);
        var credential = authorization(discoveryContext(actor, null, contextId, correlation));
        var result = github.repositories(credential.getAccessToken(), installationId, page, correlation);
        if (!Instant.now().isBefore(credential.getExpiresAt())) throw failure(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_EXPIRED);
        return result;
    }

    private GitHubAuthorizationContextEntity discoveryContext(GitHubAuthorizationUser actor, UUID workspace, UUID contextId, String correlation) {
        var access = authority.read(actor, correlation);
        UUID tenant = GitHubAuthorizationPolicy.tenant(access);
        var row = inTransaction(tenant, true, () -> {
            var context = owned(tenant, actor.getIdentityId(), contextId);
            if (workspace != null && !workspace.equals(context.getWorkspaceId())) throw failure(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_NOT_FOUND);
            GitHubAuthorizationPolicy.workspace(access, context.getWorkspaceId());
            if (!Instant.now().isBefore(context.getExpiresAt())) throw failure(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_EXPIRED);
            if (!configurationVersion(context.getAppId()).equals(context.getAppConfigVersion())) throw failure(GitHubAuthorizationError.AUTHORIZATION_CONFIGURATION_CHANGED);
            if (java.util.Set.of("PENDING", "EXCHANGING").contains(context.getStatus())) throw failure(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_NOT_READY);
            if (!"AUTHORIZED".equals(context.getStatus())) throw failure(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_CONSUMED);
            return context;
        });
        return row;
    }

    @Override
    public com.livingdocs.model.GitHubAuthorizationSelection select(GitHubAuthorizationUser actor, UUID workspace, UUID contextId,
            String installationId, int page, String correlation) {
        GitHubAuthorizationClient.validateInstallationId(installationId);
        if (page <= 0) throw failure(GitHubAuthorizationError.INVALID_REQUEST);
        var row = discoveryContext(actor, workspace, contextId, correlation);
        var credential = authorization(row);
        var candidates = github.installations(credential.getAccessToken(), row.getAppId(), page, correlation);
        var proof = candidates.getItems().stream().filter(i -> i.getInstallationId().equals(installationId)).findFirst()
                .orElseThrow(() -> failure(GitHubAuthorizationError.INSTALLATION_SELECTION_CHANGED));
        if (!Instant.now().isBefore(credential.getExpiresAt())) throw failure(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_EXPIRED);
        return new com.livingdocs.model.GitHubAuthorizationSelection(new GitHubAuthorizationContextSnapshot(contextId,row.getExpiresAt(),"AUTHORIZED"),
                proof, row.getAppId(), credential.getExpiresAt());
    }

    private GitHubAuthorizationContextEntity owned(UUID tenant, UUID actor, UUID context) {
        return contexts.findByTenantIdAndActorIdentityIdAndContextId(tenant, actor, context)
                .orElseThrow(() -> failure(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_NOT_FOUND));
    }

    private <T> T inTransaction(UUID tenant, boolean readOnly, Supplier<T> operation) {
        com.livingdocs.model.GitHubAuthorizationDeadline.check();
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setReadOnly(readOnly);
        transaction.setTimeout(2);
        try {
            return transaction.execute(status -> {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void beforeCommit(boolean ignored) { com.livingdocs.model.GitHubAuthorizationDeadline.check(); }
                        });
                com.livingdocs.model.GitHubAuthorizationDeadline.check();
                if (tenant != null) entities.createNativeQuery("select set_config('app.tenant_id', :tenant, true)")
                        .setParameter("tenant", tenant.toString()).getSingleResult();
                entities.createNativeQuery("select set_config('statement_timeout', '1500ms', true)").getSingleResult();
                return operation.get();
            });
        } catch (GitHubAuthorizationException expected) { throw expected; }
        catch (RuntimeException failed) { throw failure(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_UNAVAILABLE); }
    }

    private String configurationVersion(String appId) {
        try {
            if (!appId.matches("[1-9][0-9]{0,18}") || Long.parseLong(appId) <= 0
                    || !catalog.configuredClientId().matches("[A-Za-z0-9_.-]{1,128}")
                    || properties.getContextTtl().compareTo(java.time.Duration.ofSeconds(1)) < 0
                    || properties.getContextTtl().compareTo(java.time.Duration.ofMinutes(15)) > 0) throw new IllegalArgumentException();
            URI origin = properties.trustedUri(properties.getBrowserOrigin());
            URI callback = properties.trustedUri(properties.getCallbackUri());
            URI completion = properties.trustedUri(properties.getCompletionUri());
            if (!origin.getPath().isEmpty() || !"/api/v1/integrations/github/authorization/callback".equals(callback.getPath())
                    || !sameOrigin(origin, callback) || !sameOrigin(origin, completion)) throw new IllegalArgumentException();
            return GitHubAuthorizationContextCodec.digest(String.join("\n", "github-app-profile-v1", appId,
                    properties.getAppId(), properties.getAppSlug(), properties.getAppName(), catalog.configuredClientId(),
                    origin.toString(), callback.toString(), completion.toString()));
        } catch (RuntimeException failed) { throw failure(GitHubAuthorizationError.GITHUB_AUTHORIZATION_CONFIG_UNAVAILABLE); }
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equals(right.getScheme()) && left.getHost().equalsIgnoreCase(right.getHost()) && left.getPort() == right.getPort();
    }
    private static String query(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    private static GitHubAuthorizationException failure(GitHubAuthorizationError error) { return new GitHubAuthorizationException(error); }
}
