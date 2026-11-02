package com.notify.dlq.service;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.dlq.dto.DeadLetterEventEnvelope;
import com.notify.dlq.model.MaterializationOutcome;
import com.notify.dlq.repository.DeadLetterEntryRepository;
import com.notify.dlq.service.DeadLetterMaterializationService.RecordCoordinates;
import com.notify.notification.service.NotificationService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * No partial state when a write after the lifecycle transition fails (/ , ; ).
 *
 * <p>The transition, the entry row and the history row are one transaction. If the entry write fails, the
 * transition must roll back with it, the record must stay unacknowledged, and the redelivery must materialize
 * exactly once - so there is never a DLQ notification without its entry, nor an entry for a notification that is
 * still FAILED.
 *
 * <p>The failure is injected the way it could actually happen: the entry insert violates a real constraint. A
 * second entry for a different notification carrying the <b>same</b> {@code last_event_id} hits
 * {@code uq_dlq_last_event}, which is exactly the integrity failure the index exists for.
 */
class DlqMaterializationRollbackTest extends AbstractPostgresIntegrationTest {

    @Autowired private DeadLetterMaterializationService materialization;
    @Autowired private DeadLetterEntryRepository entries;
    @Autowired private NotificationService notificationService;
    @Autowired private TenantRepository tenants;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    private UUID tenantId;
    private String fixtureTopic;

    @BeforeEach
    void setUp() {
        tenantId = tenants.save(new TenantEntity("RollbackOrg", "rollback-" + UUID.randomUUID())).getId();
        fixtureTopic = "notify.test.dlq." + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM dlq_quarantine WHERE topic = ?", fixtureTopic);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    private UUID seedFailed() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, 'EMAIL', 'FAILED', 'someone@example.com', 'subject', 'body', 0, 1, 5, 5, now())
                """, id, tenantId);
        return id;
    }

    private DeadLetterEventEnvelope event(UUID notificationId, UUID eventId) {
        return new DeadLetterEventEnvelope(1, eventId, DeadLetterEventEnvelope.EVENT_TYPE, notificationId,
                tenantId, Channel.EMAIL, 0, 1, DlqReason.PERMANENT_FAILURE, "VALIDATION_FAILED", 1,
                clock.instant().toString());
    }

    private RecordCoordinates coordinates(long offset) {
        return new RecordCoordinates(fixtureTopic, 0, offset, "{}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an entry write that fails rolls the lifecycle transition back, leaving no partial state")
    void entryWriteFailureRollsBackTheTransition() {
        UUID taken = seedFailed();
        UUID victim = seedFailed();
        UUID sharedEventId = UUID.randomUUID();

        // The event id is already recorded against another notification, so the second insert violates
        // uq_dlq_last_event after its conditional transition has already updated the row.
        assertThat(materialization.apply(event(taken, sharedEventId), coordinates(1)))
                .isEqualTo(MaterializationOutcome.MATERIALIZED);

        assertThatThrownBy(() ->
                transactionTemplate.execute(status ->
                        materialization.apply(event(victim, sharedEventId), coordinates(2))))
                .as("the integrity failure must surface, not be swallowed")
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, victim))
                .as("the transition rolled back with the failed entry write")
                .isEqualTo("FAILED");
        assertThat(entries.findByNotificationId(victim)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_status_history WHERE notification_id = ?", Long.class, victim))
                .isZero();
    }

    @Test
    @DisplayName("the redelivery after a rollback materializes exactly once")
    void redeliveryAfterRollbackMaterializesOnce() {
        UUID id = seedFailed();
        UUID eventId = UUID.randomUUID();
        UUID taken = seedFailed();

        // First: provoke the rollback by reusing an event id that is already recorded elsewhere.
        materialization.apply(event(taken, eventId), coordinates(1));
        assertThatThrownBy(() -> transactionTemplate.execute(status ->
                materialization.apply(event(id, eventId), coordinates(2))))
                .isInstanceOf(RuntimeException.class);

        // Then: the real redelivery carries its own event id, as a real redelivery of a distinct event would.
        UUID freshEventId = UUID.randomUUID();
        assertThat(materialization.apply(event(id, freshEventId), coordinates(3)))
                .isEqualTo(MaterializationOutcome.MATERIALIZED);
        assertThat(materialization.apply(event(id, freshEventId), coordinates(4)))
                .as("and a genuine duplicate of that redelivery is a no-op")
                .isEqualTo(MaterializationOutcome.DUPLICATE);

        assertThat(jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id))
                .isEqualTo("DLQ");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM dead_letter_entries WHERE notification_id = ?", Long.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_status_history WHERE notification_id = ? AND to_status = 'DLQ'",
                Long.class, id))
                .as("exactly one FAILED to DLQ transition, however many times the record came back")
                .isEqualTo(1);
    }
}
