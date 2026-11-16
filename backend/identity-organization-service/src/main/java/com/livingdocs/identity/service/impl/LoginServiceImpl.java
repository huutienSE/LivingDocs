package com.livingdocs.identity.service.impl;

import com.livingdocs.identity.entity.Identity;
import com.livingdocs.identity.exception.InvalidCredentialsException;
import com.livingdocs.identity.model.EmailNormalization;
import com.livingdocs.identity.repository.IdentityRepository;
import com.livingdocs.identity.security.RedisLoginRateLimiter;
import com.livingdocs.identity.service.LoginService;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class LoginServiceImpl implements LoginService {

    private final IdentityRepository identities;
    private final PasswordEncoder passwords;
    private final RedisLoginRateLimiter limiter;
    private final String dummyHash;


    // constructrion và cơ chế chống timming attack với dummyHash
    public LoginServiceImpl(IdentityRepository identities, PasswordEncoder passwords, RedisLoginRateLimiter limiter) {
        this.identities = identities;
        this.passwords = passwords;
        this.limiter = limiter;
        this.dummyHash = passwords.encode("dummy-login-credential-" + UUID.randomUUID());
    }

    @Override
    public UUID verifyCredentials(String email, String rawPassword) {
        String normalized = EmailNormalization.trimOuterSpaces(email);
        if (normalized == null || rawPassword == null) {
            throw new InvalidCredentialsException();
        }

        // reate limiting với redis
        limiter.check(normalized.toLowerCase(Locale.ROOT));

        Identity identity = identities.findByNormalizedEmail(normalized).orElse(null);

        String storedHash = identity != null ? identity.passwordHashForVerification() : null;

        boolean passwordMatches = passwords.matches(rawPassword, storedHash != null ? storedHash : dummyHash);

        // không tìm thấy email
        // mật khẩu không hợp lê
        // mật khẩu nhập vào không khớp
        // tài khoản bị khóa vô hiệu hóa
        // ==> tất cả đều sẽ trả về invalid email or password
        if (identity == null || storedHash == null || !passwordMatches || !"ACTIVE".equals(identity.getStatus())) {
            throw new InvalidCredentialsException();
        }

        return identity.getIdentityId();
    }
}
