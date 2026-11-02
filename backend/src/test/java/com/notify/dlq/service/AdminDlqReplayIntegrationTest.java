package com.notify.dlq.service;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.common.model.DlqStatus;
import com.notify.common.model.TenantStatus;
import com.notify.dlq.dto.DeadLetterEventEnvelope;
import com.notify.dlq.dto.DlqEntryDetail;
import com.notify.dlq.dto.DlqReplayResponse;
import com.notify.dlq.entity.DeadLetterEntryEntity;
import com.notify.dlq.model.MaterializationOutcome;
import com.notify.dlq.repository.DeadLetterEntryRepository;
import com.notify.dlq.service.AdminDlqService.AdminActor;
import com.notify.dlq.service.DeadLetterMaterializationService.RecordCoordinates;
import com.notify.common.error.NotifyException;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Admin replay and discard (/ , ; , , to ).
 *
 * <p>Replay is one transaction: new cycle, new generation, cycle budgets reset, `claim_seq` preserved, old attempt
 * and history rows untouched, the entry REPLAYED with the **single** `replay_count` increment (), the audit
 * row, and a `NOTIFICATION_REPLAYED` outbox event - the event type that already exists on all three channels.
 */
class AdminDlqReplayIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final AtomicLong OFFSETS = new AtomicLong();
    private static final AdminActor ACTOR =
            new AdminActor(UUID.randomUUID(), "admin@notify.local", "127.0.0.1");

    @Autowired private AdminDlqService adminDlq;
    @Autowired private DeadLetterMaterializationService materialization;
    @Autowired private DeadLetterEntryRepository entries;
    @Autowired private TenantRepository tenants;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    private UUID tenantId;
    private String fixtureTopic;

    @BeforeEach
    void setUp() {
        tenantId = tenants.save(new TenantEntity("ReplayOrg", "replay-" + UUID.randomUUID())).getId();
        fixtureTopic = "notify.test.dlq." + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM dlq_quarantine WHERE topic = ?", fixtureTopic);
        jdbc.update("DELETE FROM admin_audit_log WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    /** A notification that failed, with one real attempt row, materialized into an OPEN entry. */
    private UUID failedAndMaterialized(String channel) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body, sender,
                    dispatch_generation, delivery_cycle, attempt_count, deferral_count, max_attempts, claim_seq,
                    queued_at, completed_at, processing_started_at)
                VALUES (?, ?, ?, 'FAILED', 'someone@example.com', 'subject', 'body', 'noreply@example.com',
                        0, 1, 5, 3, 5, 7, now(), now(), now())
                """, id, tenantId, channel);
        jdbc.update("""
                INSERT INTO delivery_attempts (id, tenant_id, notification_id, delivery_cycle, attempt_number,
                    provider, outcome, error_code, started_at, finished_at, latency_ms)
                VALUES (?, ?, ?, 1, 5, 'resend', 'RETRYABLE_FAILURE', 'PROVIDER_UNAVAILABLE', now(), now(), 12)
                """, UUID.randomUUID(), tenantId, id);
        DeadLetterEventEnvelope event = new DeadLetterEventEnvelope(
                1, UUID.randomUUID(), DeadLetterEventEnvelope.EVENT_TYPE, id, tenantId,
                Channel.valueOf(channel), 0, 1, DlqReason.MAX_RETRIES_EXCEEDED, "PROVIDER_UNAVAILABLE", 5,
                clock.instant().toString());
        assertThat(materialization.apply(event, new RecordCoordinates(
                fixtureTopic, 0, OFFSETS.incrementAndGet(), "{}".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(MaterializationOutcome.MATERIALIZED);
        return id;
    }

    private Map<String, Object> notification(UUID id) {
        return jdbc.queryForMap("""
                SELECT status, delivery_cycle, dispatch_generation, attempt_count, deferral_count, claim_seq,
                       max_attempts, next_retry_at, completed_at, processing_started_at, sender
                FROM notifications WHERE id = ?
                """, id);
    }

    @Test
    @DisplayName("/17.18 replay is atomic and complete, and old attempts survive")
    void replayIsAtomicAndPreservesHistory() {
        UUID id = failedAndMaterialized("EMAIL");
        DeadLetterEntryEntity before = entries.findByNotificationId(id).orElseThrow();

        DlqReplayResponse response = adminDlq.replay(before.getId(), "retrying after the provider recovered", ACTOR);

        Map<String, Object> after = notification(id);
        assertThat(after.get("status")).isEqualTo("QUEUED");
        assertThat(((Number) after.get("delivery_cycle")).intValue()).isEqualTo(2);
        assertThat(((Number) after.get("dispatch_generation")).intValue()).isEqualTo(1);
        assertThat(((Number) after.get("attempt_count")).intValue()).as("cycle budget reset").isZero();
        assertThat(((Number) after.get("deferral_count")).intValue()).as("cycle budget reset").isZero();
        assertThat(((Number) after.get("claim_seq")).intValue())
                .as("claim_seq is a monotonic lifetime fence, not a cycle budget")
                .isEqualTo(7);
        assertThat(((Number) after.get("max_attempts")).intValue()).isEqualTo(5);
        assertThat(after.get("next_retry_at")).isNull();
        assertThat(after.get("completed_at")).isNull();
        assertThat(after.get("processing_started_at")).isNull();
        assertThat(after.get("sender")).as("freezes the send-identity source").isEqualTo("noreply@example.com");

        DeadLetterEntryEntity entry = entries.findById(before.getId()).orElseThrow();
        assertThat(entry.getStatus()).isEqualTo(DlqStatus.REPLAYED);
        assertThat(entry.getReplayCount()).as("the single increment lives here").isEqualTo(1);
        assertThat(entry.getResolvedBy()).isEqualTo("admin@notify.local");
        assertThat(entry.getResolutionNote()).isEqualTo("retrying after the provider recovered");

        // The old attempt row is untouched, and the new cycle has its own space ().
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_attempts WHERE notification_id = ? "
                + "AND delivery_cycle = 1 AND attempt_number = 5", Long.class, id)).isEqualTo(1);

        // The ADMIN history row, and the dispatch event that already exists on every channel.
        Map<String, Object> history = jdbc.queryForMap("""
                SELECT from_status, to_status, actor FROM notification_status_history
                WHERE notification_id = ? ORDER BY occurred_at DESC LIMIT 1
                """, id);
        assertThat(history.get("from_status")).isEqualTo("DLQ");
        assertThat(history.get("to_status")).isEqualTo("QUEUED");
        assertThat(history.get("actor")).isEqualTo("ADMIN");

        Map<String, Object> outbox = jdbc.queryForMap("""
                SELECT event_type, topic, status FROM outbox_events
                WHERE aggregate_id = ? ORDER BY created_at DESC LIMIT 1
                """, id);
        assertThat(outbox.get("event_type"))
                .as("the existing accepted dispatch event type, so no consumer changes")
                .isEqualTo("NOTIFICATION_REPLAYED");
        assertThat(outbox.get("topic")).isEqualTo("notify.dispatch.email.v1");
        assertThat(outbox.get("status")).isEqualTo("PENDING");

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT action, target_type, target_id, admin_email, reason, details, ip_address
                FROM admin_audit_log WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 1
                """, tenantId);
        assertThat(audit.get("action")).isEqualTo("DLQ_REPLAYED");
        assertThat(audit.get("target_id")).isEqualTo(id.toString());
        assertThat(audit.get("admin_email")).isEqualTo("admin@notify.local");
        assertThat(audit.get("details").toString()).contains("newDeliveryCycle").contains("2");

        assertThat(response.newDeliveryCycle()).isEqualTo(2);
        assertThat(response.newDispatchGeneration()).isEqualTo(1);
        assertThat(response.replayCount()).isEqualTo(1);
        assertThat(response.possibleDuplicateDelivery())
                .as("an exhausted budget may have left an accepted send behind")
                .isTrue();
        assertThat(response.ambiguityNote()).contains("second external effect");
    }

    @Test
    @DisplayName("discard changes no notification status and writes no notification history row")
    void discardTouchesNoLifecycle() {
        UUID id = failedAndMaterialized("WEBHOOK");
        DeadLetterEntryEntity entry = entries.findByNotificationId(id).orElseThrow();
        long historyBefore = jdbc.queryForObject(
                "SELECT count(*) FROM notification_status_history WHERE notification_id = ?", Long.class, id);

        DlqEntryDetail detail = adminDlq.discard(entry.getId(), "receiver was decommissioned", ACTOR);

        assertThat(detail.status()).isEqualTo(DlqStatus.DISCARDED);
        assertThat(detail.resolutionNote()).isEqualTo("receiver was decommissioned");
        assertThat(detail.replayCount()).as("a discard is not a replay").isZero();
        assertThat(notification(id).get("status")).as("the notification stays DLQ").isEqualTo("DLQ");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_status_history WHERE notification_id = ?", Long.class, id))
                .isEqualTo(historyBefore);
        assertThat(jdbc.queryForObject(
                "SELECT action FROM admin_audit_log WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, tenantId))
                .isEqualTo("DLQ_DISCARDED");
    }

    @Test
    @DisplayName("replay for a non-ACTIVE tenant is refused, and nothing is written")
    void replayRefusedForInactiveTenant() {
        UUID id = failedAndMaterialized("EMAIL");
        DeadLetterEntryEntity entry = entries.findByNotificationId(id).orElseThrow();
        jdbc.update("UPDATE tenants SET status = ? WHERE id = ?", TenantStatus.SUSPENDED.name(), tenantId);

        assertThatThrownBy(() -> adminDlq.replay(entry.getId(), "note", ACTOR))
                .isInstanceOf(NotifyException.class)
                .hasMessageContaining("SUSPENDED");

        Map<String, Object> after = notification(id);
        assertThat(after.get("status")).isEqualTo("DLQ");
        assertThat(((Number) after.get("delivery_cycle")).intValue()).isEqualTo(1);
        assertThat(entries.findById(entry.getId()).orElseThrow().getStatus()).isEqualTo(DlqStatus.OPEN);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'NOTIFICATION_REPLAYED'",
                Long.class, id)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE tenant_id = ?", Long.class, tenantId)).isZero();
    }

    @Test
    @DisplayName("/17.20 a resolved entry refuses a second replay and a discard")
    void resolvedEntryRefusesFurtherActions() {
        UUID id = failedAndMaterialized("IN_APP");
        DeadLetterEntryEntity entry = entries.findByNotificationId(id).orElseThrow();

        adminDlq.replay(entry.getId(), null, ACTOR);

        assertThatThrownBy(() -> adminDlq.replay(entry.getId(), null, ACTOR))
                .as("the entry is no longer OPEN")
                .isInstanceOf(NotifyException.class);
        assertThatThrownBy(() -> adminDlq.discard(entry.getId(), "too late", ACTOR))
                .isInstanceOf(NotifyException.class);

        assertThat(entries.findById(entry.getId()).orElseThrow().getReplayCount())
                .as("exactly one increment, from the one replay that committed")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log WHERE tenant_id = ?",
                Long.class, tenantId))
                .as("and exactly one audit row")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("replay to failure to replay: replay_count is 2, one entry, three cycles readable")
    void repeatedReplayCycles() {
        UUID id = failedAndMaterialized("EMAIL");
        DeadLetterEntryEntity entry = entries.findByNotificationId(id).orElseThrow();

        // Cycle 1 failed, replay to cycle 2.
        adminDlq.replay(entry.getId(), "first replay", ACTOR);

        // Cycle 2 fails: a real attempt row, then the authoritative event reopens the entry.
        jdbc.update("""
                INSERT INTO delivery_attempts (id, tenant_id, notification_id, delivery_cycle, attempt_number,
                    provider, outcome, error_code, started_at, finished_at, latency_ms)
                VALUES (?, ?, ?, 2, 5, 'resend', 'RETRYABLE_FAILURE', 'PROVIDER_TIMEOUT', now(), now(), 30)
                """, UUID.randomUUID(), tenantId, id);
        jdbc.update("UPDATE notifications SET status = 'FAILED', attempt_count = 5 WHERE id = ?", id);
        DeadLetterEventEnvelope second = new DeadLetterEventEnvelope(
                1, UUID.randomUUID(), DeadLetterEventEnvelope.EVENT_TYPE, id, tenantId, Channel.EMAIL, 1, 2,
                DlqReason.MAX_RETRIES_EXCEEDED, "PROVIDER_TIMEOUT", 5, clock.instant().toString());
        assertThat(materialization.apply(second, new RecordCoordinates(
                fixtureTopic, 0, OFFSETS.incrementAndGet(), "{}".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(MaterializationOutcome.REOPENED);
        assertThat(entries.findById(entry.getId()).orElseThrow().getReplayCount())
                .as("the reopen does not increment ()")
                .isEqualTo(1);

        // Replay again, into cycle 3.
        DlqReplayResponse again = adminDlq.replay(entry.getId(), "second replay", ACTOR);

        assertThat(again.newDeliveryCycle()).isEqualTo(3);
        DeadLetterEntryEntity finalEntry = entries.findById(entry.getId()).orElseThrow();
        assertThat(finalEntry.getReplayCount()).as("two replays performed").isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM dead_letter_entries WHERE notification_id = ?", Long.class, id))
                .as("still one entry row for the lifetime")
                .isEqualTo(1);
        assertThat(jdbc.queryForList(
                "SELECT DISTINCT delivery_cycle FROM delivery_attempts WHERE notification_id = ? ORDER BY 1",
                Integer.class, id))
                .as("every earlier cycle attempt row survives")
                .containsExactly(1, 2);
        assertThat(((Number) notification(id).get("delivery_cycle")).intValue()).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'NOTIFICATION_REPLAYED'",
                Long.class, id)).isEqualTo(2);
    }
}
