package com.livingdocs.identity.service.impl;

import com.livingdocs.identity.entity.Identity;
import com.livingdocs.identity.exception.IdentityAlreadyExistsException;
import com.livingdocs.identity.model.EmailNormalization;
import com.livingdocs.identity.repository.IdentityRepository;
import com.livingdocs.identity.service.RegistrationService;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

@Service
@Validated
@RequiredArgsConstructor
public class RegistrationServiceImpl implements RegistrationService {

    private static final String EMAIL_UNIQUE_INDEX = "uq_identities_email";
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private final IdentityRepository identityRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public UUID registerLocal(String email, String rawPassword) {
        String normalizedEmail = EmailNormalization.trimOuterSpaces(email);
        String encodedPassword = passwordEncoder.encode(rawPassword);

        try {
            return identityRepository.saveAndFlush(new Identity(normalizedEmail, encodedPassword))
                    .getIdentityId();
        } catch (DataIntegrityViolationException ex) {
            if (isEmailUniqueViolation(ex)) {
                throw new IdentityAlreadyExistsException(ex);
            }
            throw ex;
        }
    }

    private boolean isEmailUniqueViolation(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && UNIQUE_VIOLATION_SQL_STATE.equals(violation.getSQLState())
                    && EMAIL_UNIQUE_INDEX.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
