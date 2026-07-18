package com.zhiguang.be.auth.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRequestValidationTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void loginRequestShouldRequirePasswordOrCaptchaCode() {
        Set<ConstraintViolation<LoginRequest>> violations = VALIDATOR.validate(
                new LoginRequest("13800138000", null, "h5", null)
        );

        assertTrue(violations.stream()
                .anyMatch(violation -> "password or captchaCode is required".equals(violation.getMessage())));
    }

    @Test
    void loginRequestShouldAllowPasswordLoginWithoutCaptchaCode() {
        Set<ConstraintViolation<LoginRequest>> violations = VALIDATOR.validate(
                new LoginRequest("13800138000", "Passw0rd!", "h5", null)
        );

        assertFalse(hasCredentialViolation(violations));
    }

    @Test
    void loginRequestShouldAllowCaptchaLoginWithoutPassword() {
        Set<ConstraintViolation<LoginRequest>> violations = VALIDATOR.validate(
                new LoginRequest("13800138000", null, "h5", "123456")
        );

        assertFalse(hasCredentialViolation(violations));
    }

    private boolean hasCredentialViolation(Set<ConstraintViolation<LoginRequest>> violations) {
        return violations.stream()
                .anyMatch(violation -> "password or captchaCode is required".equals(violation.getMessage()));
    }
}
