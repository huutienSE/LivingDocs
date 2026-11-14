package com.livingdocs.identity;

import com.livingdocs.identity.entity.Identity;
import com.livingdocs.identity.exception.IdentityAlreadyExistsException;
import com.livingdocs.identity.repository.IdentityRepository;
import com.livingdocs.identity.service.impl.RegistrationServiceImpl;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationServiceImplTest {

    private IdentityRepository identities;
    private PasswordEncoder encoder;
    private RegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        identities = mock(IdentityRepository.class);
        encoder = mock(PasswordEncoder.class);
        service = new RegistrationServiceImpl(identities, encoder);
    }

    @Test
    void normalizesAndHashesOnceBeforeSaving() {
        when(encoder.encode("A-secure-password-123")).thenReturn("bcrypt-hash");
        when(identities.saveAndFlush(any(Identity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.registerLocal("  Person@Example.com  ", "A-secure-password-123");

        ArgumentCaptor<Identity> saved = ArgumentCaptor.forClass(Identity.class);
        verify(identities).saveAndFlush(saved.capture());
        verify(encoder, times(1)).encode("A-secure-password-123");
        assertThat(saved.getValue().getEmail()).isEqualTo("Person@Example.com");
        assertThat(saved.getValue().passwordHashForVerification()).isEqualTo("bcrypt-hash");
    }

    @Test
    void convertsOnlyNamedEmailUniqueViolation() {
        when(encoder.encode(any())).thenReturn("bcrypt-hash");
        when(identities.saveAndFlush(any(Identity.class))).thenThrow(
                violation("23505", "uq_identities_email"));

        assertThatThrownBy(() -> service.registerLocal("person@example.com", "A-secure-password-123"))
                .isInstanceOf(IdentityAlreadyExistsException.class);
    }

    @Test
    void leavesOtherDatabaseFailuresAsDatabaseFailures() {
        when(encoder.encode(any())).thenReturn("bcrypt-hash");
        when(identities.saveAndFlush(any(Identity.class))).thenThrow(
                violation("23505", "other_constraint"));

        assertThatThrownBy(() -> service.registerLocal("person@example.com", "A-secure-password-123"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(IdentityAlreadyExistsException.class);
    }

    private DataIntegrityViolationException violation(String sqlState, String name) {
        SQLException sql = new SQLException("test database failure", sqlState);
        return new DataIntegrityViolationException("test database failure",
                new ConstraintViolationException("test database failure", sql, name));
    }
}
