package com.notify.dlq.service;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.common.model.DlqStatus;
import com.notify.common.model.NotificationStatus;
import com.notify.dlq.dto.DeadLetterEventEnvelope;
import com.notify.dlq.entity.DeadLetterEntryEntity;
import com.notify.dlq.model.MaterializationOutcome;
import com.notify.dlq.model.QuarantineAssociation;
import com.notify.dlq.repository.DeadLetterEntryRepository;
import com.notify.dlq.repository.DlqQuarantineRepository;
import com.notify.dlq.service.DeadLetterMaterializationService.RecordCoordinates;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exhaustive materialization outcome matrix (/ , section 2.2; to , ,
 * ).
 *
 * <p>Every reachable state has exactly one outcome, and the two silent no-ops are **proofs** - the entry records
 * this event, or the row demonstrably moved past this cycle. Every other inapplicable record is quarantined, and
 * in each of those cases this suite asserts that the notification, the entry, the history and `replay_count` are
 * **unchanged**: a record that cannot be explained must not look like one that was already handled.
 *
 * <p>Fixture-owned cleanup: each test deletes its own tenant (which cascades) and its own quarantine rows by the
 * synthetic topic it generated. Nothing is deleted by age, by status, or by any predicate that could match another
 * suite rows.
 */
class DlqMaterializationOutcomeMatrixTest extends AbstractPostgresIntegrationTest {

    private static final AtomicInteger OFFSETS = new AtomicInteger();

    @Autowired private DeadLetterMaterializationService materialization;
    @Autowired private DeadLetterEntryRepository entries;
    @Autowired private DlqQuarantineRepository quarantine;
    @Autowired private TenantRepository tenants;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;
    private String fixtureTopic;

    @BeforeEach
    void setUp() {
        tenantId = tenants.save(new TenantEntity("MatrixOrg", "matrix-" + UUID.randomUUID())).getId();
        fixtureTopic = "notify.test.dlq." + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM dlq_quarantine WHERE topic = ?", fixtureTopic);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    private UUID seedNotification(NotificationStatus status, int cycle, int generation, int attemptCount) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, 'EMAIL', ?, 'someone@example.com', 'subject', 'body', ?, ?, ?, 5, now())
                """, id, tenantId, status.name(), generation, cycle, attemptCount);
        return id;
    }

    private DeadLetterEventEnvelope event(UUID notificationId, int cycle, int generation) {
        return new DeadLetterEventEnvelope(1, UUID.randomUUID(), DeadLetterEventEnvelope.EVENT_TYPE,
                notificationId, tenantId, Channel.EMAIL, generation, cycle,
                DlqReason.MAX_RETRIES_EXCEEDED, "PROVIDER_UNAVAILABLE", 5, "2026-10-17T10:00:00Z");
    }

    private RecordCoordinates coordinates() {
        return new RecordCoordinates(fixtureTopic, 0, OFFSETS.incrementAndGet(),
                "{}".getBytes(StandardCharsets.UTF_8));
    }

    private MaterializationOutcome apply(DeadLetterEventEnvelope event) {
        return materialization.apply(event, coordinates());
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id);
    }

    private long historyCount(UUID id) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM notification_status_history WHERE notification_id = ?", Long.class, id);
        return count == null ? 0 : count;
    }

    private long quarantineCount() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM dlq_quarantine WHERE topic = ?", Long.class, fixtureTopic);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("row 6: first materialization writes one entry, one status change and one history row")
    void firstMaterialization() {
        UUID id = seedNotification(NotificationStatus.FAILED, 1, 0, 5);

        assertThat(apply(event(id, 1, 0))).isEqualTo(MaterializationOutcome.MATERIALIZED);

        assertThat(statusOf(id)).isEqualTo("DLQ");
        DeadLetterEntryEntity entry = entries.findByNotificationId(id).orElseThrow();
        assertThat(entry.getStatus()).isEqualTo(DlqStatus.OPEN);
        assertThat(entry.getReason()).isEqualTo(DlqReason.MAX_RETRIES_EXCEEDED);
        assertThat(entry.getAttemptCount()).isEqualTo(5);
        assertThat(entry.getSourceDeliveryCycle()).isEqualTo(1);
        assertThat(entry.getSourceGeneration()).isZero();
        assertThat(entry.getReplayCount()).isZero();
        assertThat(entry.getPayloadSnapshot())
                .as("the snapshot is the dead-letter envelope: no recipient, subject or body")
                .contains("NOTIFICATION_DEAD_LETTERED")
                .doesNotContain("someone@example.com");
        assertThat(historyCount(id)).isEqualTo(1);
        assertThat(quarantineCount()).isZero();
    }

    @Test
    @DisplayName("row 2: a duplicate of the applied event changes nothing at all")
    void duplicateChangesNothing() {
        UUID id = seedNotification(NotificationStatus.FAILED, 1, 0, 5);
        DeadLetterEventEnvelope first = event(id, 1, 0);
        assertThat(apply(first)).isEqualTo(MaterializationOutcome.MATERIALIZED);
        long historyAfterFirst = historyCount(id);
        DeadLetterEntryEntity before = entries.findByNotificationId(id).orElseThrow();

        assertThat(apply(first)).isEqualTo(MaterializationOutcome.DUPLICATE);

        DeadLetterEntryEntity after = entries.findByNotificationId(id).orElseThrow();
        assertThat(after.getReplayCount()).isEqualTo(before.getReplayCount());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(historyCount(id)).isEqualTo(historyAfterFirst);
        assertThat(statusOf(id)).isEqualTo("DLQ");
        assertThat(quarantineCount()).as("a proven duplicate is not an inconsistency").isZero();
    }

    @Test
    @DisplayName("rows 3 and 10: a superseded event is stale, and nothing is written")
    void staleEventChangesNothing() {
        UUID id = seedNotification(NotificationStatus.FAILED, 2, 1, 2);

        assertThat(apply(event(id, 1, 0))).isEqualTo(MaterializationOutcome.STALE);

        assertThat(entries.findByNotificationId(id)).isEmpty();
        assertThat(statusOf(id)).isEqualTo("FAILED");
        assertThat(historyCount(id)).isZero();
        assertThat(quarantineCount()).isZero();
    }

    @Test
    @DisplayName("row 12: an event for a notification that is now DELIVERED quarantines, never applies")
    void deliveredNotificationIsQuarantined() {
        UUID id = seedNotification(NotificationStatus.DELIVERED, 1, 0, 1);

        assertThat(apply(event(id, 1, 0))).isEqualTo(MaterializationOutcome.QUARANTINED);

        assertThat(statusOf(id)).as("the lifecycle is untouched").isEqualTo("DELIVERED");
        assertThat(entries.findByNotificationId(id)).isEmpty();
        assertThat(historyCount(id)).isZero();
        assertThat(quarantineCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("rows 1, 4, 5 and 11: every inconsistent state quarantines and changes nothing")
    void inconsistentStatesQuarantine() {
        // Row 1: no notification with this id.
        assertThat(apply(event(UUID.randomUUID(), 1, 0))).isEqualTo(MaterializationOutcome.QUARANTINED);

        // Row 4: an event from a cycle the row never reached.
        UUID ahead = seedNotification(NotificationStatus.FAILED, 1, 0, 5);
        assertThat(apply(event(ahead, 3, 0))).isEqualTo(MaterializationOutcome.QUARANTINED);
        assertThat(statusOf(ahead)).isEqualTo("FAILED");

        // Row 5: same cycle, different generation.
        UUID otherGeneration = seedNotification(NotificationStatus.FAILED, 1, 4, 5);
        assertThat(apply(event(otherGeneration, 1, 0))).isEqualTo(MaterializationOutcome.QUARANTINED);
        assertThat(statusOf(otherGeneration)).isEqualTo("FAILED");

        // Row 11: DLQ with no entry to explain it.
        UUID orphanDlq = seedNotification(NotificationStatus.DLQ, 1, 0, 5);
        assertThat(apply(event(orphanDlq, 1, 0))).isEqualTo(MaterializationOutcome.QUARANTINED);
        assertThat(entries.findByNotificationId(orphanDlq)).isEmpty();

        assertThat(historyCount(ahead) + historyCount(otherGeneration) + historyCount(orphanDlq))
                .as("no quarantine-only outcome writes a history row")
                .isZero();
        assertThat(quarantineCount()).isEqualTo(4);
        assertThat(quarantine.searchQuarantine(QuarantineAssociation.UNKNOWN_NOTIFICATION, null, fixtureTopic,
                null, null, null, null, null, 10))
                .as("the missing notification is classified as such, with no invented id")
                .hasSize(1);
    }

    @Test
    @DisplayName("row 9: a legacy entry with null markers quarantines, never reopens and assumes nothing")
    void legacyNullMarkerEntryIsQuarantined() {
        UUID id = seedNotification(NotificationStatus.FAILED, 2, 1, 3);
        // Exactly what a pre-row looks like: no last_event_id, no source cycle, no source generation.
        jdbc.update("""
                INSERT INTO dead_letter_entries (tenant_id, notification_id, channel, reason, last_error_code,
                    attempt_count, status, replay_count)
                VALUES (?, ?, 'EMAIL', 'PERMANENT_FAILURE', 'LEGACY', 1, 'OPEN', 1)
                """, tenantId, id);
        DeadLetterEntryEntity before = entries.findByNotificationId(id).orElseThrow();

        assertThat(apply(event(id, 2, 1))).isEqualTo(MaterializationOutcome.QUARANTINED);

        DeadLetterEntryEntity after = entries.findByNotificationId(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(before.getStatus());
        assertThat(after.getReason()).isEqualTo(DlqReason.PERMANENT_FAILURE);
        assertThat(after.getReplayCount()).isEqualTo(1);
        assertThat(after.getLastEventId()).isNull();
        assertThat(after.getSourceDeliveryCycle()).isNull();
        assertThat(statusOf(id)).isEqualTo("FAILED");
        assertThat(historyCount(id)).isZero();
        assertThat(quarantineCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("row 7: a later cycle reopens the entry, and replay_count stays where it was")
    void replayThenFailReopens() {
        UUID id = seedNotification(NotificationStatus.FAILED, 1, 0, 5);
        assertThat(apply(event(id, 1, 0))).isEqualTo(MaterializationOutcome.MATERIALIZED);

        // The admin replayed it (cycle 2, generation 1) and the new cycle failed again.
        jdbc.update("""
                UPDATE notifications SET status = 'FAILED', delivery_cycle = 2, dispatch_generation = 1,
                    attempt_count = 5 WHERE id = ?
                """, id);
        jdbc.update("UPDATE dead_letter_entries SET status = 'REPLAYED', replay_count = 1 WHERE notification_id = ?",
                id);

        assertThat(apply(event(id, 2, 1))).isEqualTo(MaterializationOutcome.REOPENED);

        DeadLetterEntryEntity entry = entries.findByNotificationId(id).orElseThrow();
        assertThat(entry.getStatus()).isEqualTo(DlqStatus.OPEN);
        assertThat(entry.getSourceDeliveryCycle()).isEqualTo(2);
        assertThat(entry.getSourceGeneration()).isEqualTo(1);
        assertThat(entry.getReplayCount())
                .as("a reopen never increments; the single increment is in the admin replay")
                .isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("DLQ");
        assertThat(historyCount(id)).isEqualTo(2);
        assertThat(quarantineCount()).isZero();
    }
}
