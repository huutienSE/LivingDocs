package com.livingdocs.config;

import java.net.URI;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter @Setter
@ConfigurationProperties("livingdocs.github.authorization")
public class GitHubAuthorizationProperties {
    private String issuer = "urn:livingdocs:s01";
    private String audience = "livingdocs-api";
    private String jwkSetUri = "";
    private String s01BaseUri = "";
    private String appId = "";
    private String appSlug = "";
    private String appName = "";
    private boolean allowLocalHttp;
    private String clientId = "";
    private String clientSecretPath = "";
    private String browserOrigin = "";
    private String callbackUri = "";
    private String completionUri = "";
    private String stateKeyDirectory = "";
    private String credentialKeyDirectory = "";
    private String stateKeyVersion = "v1";
    private String credentialKeyVersion = "v1";
    private java.time.Duration contextTtl = java.time.Duration.ofMinutes(15);
    private String apiBaseUri = "https://api.github.com";
    private String oauthBaseUri = "https://github.com";

    public URI trustedUri(String value) {
        URI uri = URI.create(value);
        boolean local = allowLocalHttp && "http".equals(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
        if ((!local && !"https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Trusted HTTPS endpoint required");
        }
        return uri;
    }
}
