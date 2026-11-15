package com.livingdocs.identity;

import com.livingdocs.identity.security.RedisLoginRateLimiter;
import com.livingdocs.identity.security.RedisLoginRateLimiter.LoginSecurityUnavailableException;
import com.livingdocs.identity.entity.Identity;
import com.livingdocs.identity.exception.InvalidCredentialsException;
import com.livingdocs.identity.repository.IdentityRepository;
import com.livingdocs.identity.service.impl.LoginServiceImpl;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisLoginRateLimiterTest {

    @Test
    void absentPepperFailsClosedBeforeRedisAccess() {
        RedisLoginRateLimiter limiter = new RedisLoginRateLimiter(mock(StringRedisTemplate.class), "");
        assertThatThrownBy(() -> limiter.check("person@example.com"))
                .isInstanceOf(LoginSecurityUnavailableException.class);
    }

    @Test
    void realCredentialAndUnknownEmailUseBcryptWithoutMembership() {
        IdentityRepository repository = mock(IdentityRepository.class);
        RedisLoginRateLimiter limiter = mock(RedisLoginRateLimiter.class);
        PasswordEncoder encoder = spy(new BCryptPasswordEncoder(10));
        UUID identityId = UUID.randomUUID();
        Identity identity = new Identity("person@example.com", encoder.encode("correct-password"));
        ReflectionTestUtils.setField(identity, "identityId", identityId);
        when(repository.findByNormalizedEmail("person@example.com")).thenReturn(Optional.of(identity));
        when(repository.findByNormalizedEmail("Person@Example.com")).thenReturn(Optional.of(identity));
        LoginServiceImpl login = new LoginServiceImpl(repository, encoder, limiter);

        assertThat(login.verifyCredentials(" Person@Example.com ", "correct-password"))
                .isEqualTo(identityId);
        clearInvocations(encoder);
        assertThatThrownBy(() -> login.verifyCredentials("person@example.com", "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");
        verify(encoder).matches(eq("wrong-password"), anyString());

        clearInvocations(encoder);
        assertThatThrownBy(() -> login.verifyCredentials("unknown@example.com", "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");
        verify(encoder).matches(eq("wrong-password"), anyString());
    }
}
