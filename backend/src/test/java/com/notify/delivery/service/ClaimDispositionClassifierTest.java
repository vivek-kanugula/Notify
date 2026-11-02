package com.notify.delivery.service;

import com.notify.common.model.NotificationStatus;
import com.notify.delivery.model.ClaimDisposition;
import com.notify.notification.dto.NotificationEvidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** O4: all eight dispositions across status x generation x due (plan section 6.2). */
class ClaimDispositionClassifierTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private final ClaimDispositionClassifier classifier = new ClaimDispositionClassifier();

    private static Optional<NotificationEvidence> row(NotificationStatus status, int generation, Instant nextRetryAt) {
        return Optional.of(new NotificationEvidence(status, generation, 1, 1, 1, 5, 0, nextRetryAt, null));
    }

    private ClaimDisposition classify(Optional<NotificationEvidence> evidence, int eventGeneration) {
        return classifier.classify(evidence, eventGeneration, NOW);
    }

    @Test
    @DisplayName("O4 row 1: no row")
    void rowAbsent() {
        assertThat(classify(Optional.empty(), 3)).isEqualTo(ClaimDisposition.ROW_ABSENT);
    }

    @Test
    @DisplayName("O4 row 2: terminal statuses win over any generation relation")
    void terminal() {
        for (NotificationStatus status : new NotificationStatus[]{
                NotificationStatus.DELIVERED, NotificationStatus.FAILED, NotificationStatus.DLQ, NotificationStatus.CANCELLED}) {
            assertThat(classify(row(status, 3, null), 3)).as(status + " same gen").isEqualTo(ClaimDisposition.TERMINAL);
            assertThat(classify(row(status, 5, null), 3)).as(status + " newer db gen").isEqualTo(ClaimDisposition.TERMINAL);
            assertThat(classify(row(status, 1, null), 3)).as(status + " older db gen").isEqualTo(ClaimDisposition.TERMINAL);
        }
    }

    @Test
    @DisplayName("O4 rows 3 and 4: generation comparison for non-terminal rows")
    void generationRelation() {
        for (NotificationStatus status : new NotificationStatus[]{
                NotificationStatus.QUEUED, NotificationStatus.PROCESSING, NotificationStatus.RETRYING,
                NotificationStatus.SCHEDULED, NotificationStatus.CREATED}) {
            assertThat(classify(row(status, 4, NOW.plusSeconds(60)), 3)).as(status.name()).isEqualTo(ClaimDisposition.STALE_GENERATION);
            assertThat(classify(row(status, 2, NOW.plusSeconds(60)), 3)).as(status.name()).isEqualTo(ClaimDisposition.GENERATION_AHEAD);
        }
    }

    @Test
    @DisplayName("O4 row 5: RETRYING and not yet due repairs the trigger and acknowledges")
    void retryPending() {
        assertThat(classify(row(NotificationStatus.RETRYING, 3, NOW.plusSeconds(1)), 3)).isEqualTo(ClaimDisposition.RETRY_PENDING);
        assertThat(classify(row(NotificationStatus.RETRYING, 3, NOW.plusSeconds(3_600)), 3)).isEqualTo(ClaimDisposition.RETRY_PENDING);
    }

    @Test
    @DisplayName("O4 row 6: PROCESSING at this generation is in progress elsewhere")
    void inProgressElsewhere() {
        assertThat(classify(row(NotificationStatus.PROCESSING, 3, null), 3)).isEqualTo(ClaimDisposition.IN_PROGRESS_ELSEWHERE);
    }

    @Test
    @DisplayName("O4 row 7: claimable now but the claim returned zero rows is a claim race")
    void claimRace() {
        assertThat(classify(row(NotificationStatus.QUEUED, 3, null), 3)).isEqualTo(ClaimDisposition.CLAIM_RACE);
        assertThat(classify(row(NotificationStatus.RETRYING, 3, NOW), 3)).as("due exactly now").isEqualTo(ClaimDisposition.CLAIM_RACE);
        assertThat(classify(row(NotificationStatus.RETRYING, 3, NOW.minusSeconds(30)), 3)).isEqualTo(ClaimDisposition.CLAIM_RACE);
    }

    @Test
    @DisplayName("O4 row 8: states a dispatch event should never meet")
    void unexpectedState() {
        assertThat(classify(row(NotificationStatus.CREATED, 3, null), 3)).isEqualTo(ClaimDisposition.UNEXPECTED_STATE);
        assertThat(classify(row(NotificationStatus.SCHEDULED, 3, null), 3)).isEqualTo(ClaimDisposition.UNEXPECTED_STATE);
        assertThat(classify(row(NotificationStatus.RETRYING, 3, null), 3)).as("RETRYING with NULL next_retry_at").isEqualTo(ClaimDisposition.UNEXPECTED_STATE);
    }

    @Test
    @DisplayName("O4 the table is total: every status x generation relation x due state yields a disposition")
    void tableIsTotal() {
        for (NotificationStatus status : NotificationStatus.values()) {
            for (int dbGeneration : new int[]{2, 3, 4}) {
                for (Instant retryAt : new Instant[]{null, NOW.minusSeconds(5), NOW.plusSeconds(5)}) {
                    assertThat(classify(row(status, dbGeneration, retryAt), 3)).isNotNull();
                }
            }
        }
    }
}
