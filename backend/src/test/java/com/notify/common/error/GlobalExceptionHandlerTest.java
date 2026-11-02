package com.notify.common.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic classification tests for the DataIntegrityViolationException mapping.
 * Proves the active-template-name race maps to 422 while unrelated integrity faults
 * stay 500 and are never masked as validation errors.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("uq_templates_active_name violation maps to 422 VALIDATION_FAILED")
    void activeTemplateNameRaceMapsTo422() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException(
                        "ERROR: duplicate key value violates unique constraint \"uq_templates_active_name\""));

        ResponseEntity<ProblemDetail> resp = handler.handleDataIntegrityViolation(ex);

        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getProperties()).containsEntry("errorCode", "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("Unrelated unique constraint violation stays 500 INTERNAL_ERROR")
    void unrelatedConstraintStays500() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException(
                        "ERROR: duplicate key value violates unique constraint \"uq_template_versions_version\""));

        ResponseEntity<ProblemDetail> resp = handler.handleDataIntegrityViolation(ex);

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getProperties()).containsEntry("errorCode", "INTERNAL_ERROR");
    }

    @Test
    @DisplayName("Integrity violation with no identifiable constraint stays 500 INTERNAL_ERROR")
    void unknownIntegrityViolationStays500() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("opaque failure");

        ResponseEntity<ProblemDetail> resp = handler.handleDataIntegrityViolation(ex);

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getProperties()).containsEntry("errorCode", "INTERNAL_ERROR");
    }
}
