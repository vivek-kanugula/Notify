package com.notify.delivery.service;

import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.delivery.model.ReconciliationOutcome;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.notification.dto.NotificationEvidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** O5 (unit half): ambiguous-commit interpretation (plan section 7). */
class ReconciliationEvaluatorTest {

    private final ReconciliationEvaluator evaluator = new ReconciliationEvaluator();

    private static final ClaimSnapshot CLAIM = new ClaimSnapshot(
            UUID.randomUUID(), UUID.randomUUID(), 4, 7, 1, 2, 5, 0,
            Channel.EMAIL, "r@example.com", "s", "b", null, null, "from@example.com", java.time.Instant.parse("2026-10-11T12:00:00Z"), java.time.Instant.parse("2026-10-11T11:00:00Z"));

    private static Optional<NotificationEvidence> evidence(NotificationStatus status, int generation, int claimSeq) {
        return Optional.of(new NotificationEvidence(status, generation, claimSeq, 1, 2, 5, 0, null, null));
    }

    @Test
    @DisplayName("O5 no row is ABSENT")
    void absent() {
        assertThat(evaluator.evaluate(CLAIM, Optional.empty())).isEqualTo(ReconciliationOutcome.ABSENT);
    }

    @Test
    @DisplayName("O5 a different generation or claim_seq means the claim was superseded, whatever the status")
    void superseded() {
        for (NotificationStatus status : NotificationStatus.values()) {
            assertThat(evaluator.evaluate(CLAIM, evidence(status, 5, 7))).as(status + " gen differs").isEqualTo(ReconciliationOutcome.SUPERSEDED);
            assertThat(evaluator.evaluate(CLAIM, evidence(status, 4, 8))).as(status + " claim_seq newer").isEqualTo(ReconciliationOutcome.SUPERSEDED);
            assertThat(evaluator.evaluate(CLAIM, evidence(status, 4, 6))).as(status + " claim_seq older").isEqualTo(ReconciliationOutcome.SUPERSEDED);
        }
    }

    @Test
    @DisplayName("O5 PROCESSING at the same generation and claim_seq is unresolved: the result was not visible")
    void unresolved() {
        assertThat(evaluator.evaluate(CLAIM, evidence(NotificationStatus.PROCESSING, 4, 7))).isEqualTo(ReconciliationOutcome.UNRESOLVED);
    }

    @Test
    @DisplayName("O5 any other status at the same generation and claim_seq is this claim's own committed result")
    void committed() {
        for (NotificationStatus status : new NotificationStatus[]{
                NotificationStatus.DELIVERED, NotificationStatus.RETRYING, NotificationStatus.FAILED,
                NotificationStatus.DLQ, NotificationStatus.CANCELLED}) {
            assertThat(evaluator.evaluate(CLAIM, evidence(status, 4, 7))).as(status.name()).isEqualTo(ReconciliationOutcome.COMMITTED);
        }
    }
}
