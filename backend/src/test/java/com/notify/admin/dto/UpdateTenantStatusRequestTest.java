package com.notify.admin.dto;

import com.notify.common.model.TenantStatus;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateTenantStatusRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("Valid status and reason (>= 3 chars) produces zero violations")
    void validRequestPassesValidation() {
        UpdateTenantStatusRequest req = new UpdateTenantStatusRequest(TenantStatus.SUSPENDED, "Investigation pending");
        Set<ConstraintViolation<UpdateTenantStatusRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Minimum 3-character floor enforced on reason")
    void reasonShorterThanThreeCharsFailsValidation() {
        UpdateTenantStatusRequest req = new UpdateTenantStatusRequest(TenantStatus.SUSPENDED, "ab");
        Set<ConstraintViolation<UpdateTenantStatusRequest>> violations = validator.validate(req);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).contains("reason must be between 3 and 512 characters");
    }

    @Test
    @DisplayName("Exactly 3-character reason satisfies the floor")
    void threeCharacterReasonPassesValidation() {
        UpdateTenantStatusRequest req = new UpdateTenantStatusRequest(TenantStatus.SUSPENDED, "abc");
        Set<ConstraintViolation<UpdateTenantStatusRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Blank reason fails validation")
    void blankReasonFailsValidation() {
        UpdateTenantStatusRequest req = new UpdateTenantStatusRequest(TenantStatus.SUSPENDED, "   ");
        Set<ConstraintViolation<UpdateTenantStatusRequest>> violations = validator.validate(req);
        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("Null reason fails validation")
    void nullReasonFailsValidation() {
        UpdateTenantStatusRequest req = new UpdateTenantStatusRequest(TenantStatus.SUSPENDED, null);
        Set<ConstraintViolation<UpdateTenantStatusRequest>> violations = validator.validate(req);
        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("Null status fails validation")
    void nullStatusFailsValidation() {
        UpdateTenantStatusRequest req = new UpdateTenantStatusRequest(null, "Investigation pending");
        Set<ConstraintViolation<UpdateTenantStatusRequest>> violations = validator.validate(req);
        assertThat(violations).hasSize(1);
    }
}
