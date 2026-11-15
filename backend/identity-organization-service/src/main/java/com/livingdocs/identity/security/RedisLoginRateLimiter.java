package com.livingdocs.identity.security;

import com.livingdocs.identity.exception.LoginRateLimitedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisLoginRateLimiter {

    private static final DefaultRedisScript<Long> LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local global = redis.call('INCR', KEYS[1])
            if global == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1])) end
            local identity = redis.call('INCR', KEYS[2])
            if identity == 1 then redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2])) end
            local retry = 0
            if global > tonumber(ARGV[3]) then retry = math.max(retry, redis.call('TTL', KEYS[1])) end
            if identity > tonumber(ARGV[4]) then retry = math.max(retry, redis.call('TTL', KEYS[2])) end
            return retry == 0 and 0 or math.max(retry, 1)
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String pepperPath;
    private volatile byte[] pepper;

    public RedisLoginRateLimiter(StringRedisTemplate redis, @Value("${livingdocs.security.login.rate-limit.pepper-path:}") String pepperPath) {
        this.redis = redis;
        this.pepperPath = pepperPath;
    }

    public void check(String normalizedEmail) {
        String digest = digest(normalizedEmail.toLowerCase(Locale.ROOT));
        try {
            Long retry = redis.execute(LIMIT_SCRIPT, List.of("ld:login:global", "ld:login:id:" + digest),
                    "60", "300", "300", "10");
            if (retry == null) {
                throw new LoginSecurityUnavailableException();
            }
            if (retry > 0) {
                throw new LoginRateLimitedException(retry);
            }
        } catch (DataAccessException ex) {
            throw new LoginSecurityUnavailableException();
        }
    }

    // hàm băm
    private String digest(String email) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(email.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new LoginSecurityUnavailableException();
        }
    }

    private byte[] pepper() {
        byte[] current = pepper;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (pepper == null) {
                if (pepperPath.isBlank()) {
                    throw new LoginSecurityUnavailableException();
                }
                try {
                    byte[] loaded = Files.readAllBytes(Path.of(pepperPath));
                    if (loaded.length < 32) {
                        throw new LoginSecurityUnavailableException();
                    }
                    pepper = loaded;
                } catch (IOException ex) {
                    throw new LoginSecurityUnavailableException();
                }
            }
            return pepper;
        }
    }

    public static final class LoginSecurityUnavailableException extends RuntimeException {
        public LoginSecurityUnavailableException() {
            super("Login security dependency is unavailable");
        }
    }
}
