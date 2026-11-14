package com.livingdocs.identity;

import com.livingdocs.identity.dto.request.RegisterRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    void trimsOnlyOuterAsciiSpacesBeforeValidatingEmail() {
        RegisterRequest request = request("  Person@Example.com  ", "A-secure-password-123");
        assertThat(request.getEmail()).isEqualTo("Person@Example.com");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void validatesCharacterAndUtf8ByteLimits() {
        assertThat(validator.validate(request("person@example.com", "short"))).isNotEmpty();
        assertThat(validator.validate(request("person@example.com", "é".repeat(36)))).isEmpty();
        assertThat(validator.validate(request("person@example.com", "é".repeat(37))))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("password"));
        assertThat(validator.validate(request("person@example.com", "A".repeat(65)))).isNotEmpty();
    }

    private RegisterRequest request(String email, String password) {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(email);
        request.setPassword(password);
        return request;
    }
}
