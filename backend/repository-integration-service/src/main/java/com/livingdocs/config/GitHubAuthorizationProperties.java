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
