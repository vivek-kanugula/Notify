package com.notify.dlq.service;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.common.model.DlqStatus;
import com.notify.common.model.TenantStatus;
import com.notify.dlq.dto.DeadLetterEventEnvelope;
import com.notify.dlq.entity.DeadLetterEntryEntity;
import com.notify.dlq.repository.DeadLetterEntryRepository;
import com.notify.dlq.service.AdminDlqService.AdminActor;
import com.notify.dlq.service.DeadLetterMaterializationService.RecordCoordinates;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coordinated races around an admin action (/ , ; , , ).
 *
 * <p>Every case is latch-coordinated so the two transactions genuinely overlap, and every assertion is about an
 * invariant rather than about who won: exactly one winner, no partial state, exactly one audit row for the action
 * that committed, and no deadlock. The fixed lock order - tenants, notifications, dead_letter_entries, then
 * append-only writes - is what makes that possible.
 */
class DlqConcurrentActionIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final AtomicLong OFFSETS = new AtomicLong();
    private static final AdminActor ACTOR_A =
            new AdminActor(UUID.randomUUID(), "admin-a@notify.local", "127.0.0.1");
    private static final AdminActor ACTOR_B =
            new AdminActor(UUID.randomUUID(), "admin-b@notify.local", "127.0.0.2");

    @Autowired private AdminDlqService adminDlq;
    @Autowired private DeadLetterMaterializationService materialization;
    @Autowired private DeadLetterEntryRepository entries;
    @Autowired private TenantRepository tenants;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    private UUID tenantId;
    private String fixtureTopic;

    @BeforeEach
    void setUp() {
        tenantId = tenants.save(new TenantEntity("RaceOrg", "race-" + UUID.randomUUID())).getId();
        fixtureTopic = "notify.test.dlq." + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM dlq_quarantine WHERE topic = ?", fixtureTopic);
        jdbc.update("DELETE FROM admin_audit_log WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    private UUID materializedEntry() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, claim_seq, queued_at)
                VALUES (?, ?, 'EMAIL', 'FAILED', 'someone@example.com', 'subject', 'body', 0, 1, 5, 5, 3, now())
                """, id, tenantId);
        DeadLetterEventEnvelope event = new DeadLetterEventEnvelope(
                1, UUID.randomUUID(), DeadLetterEventEnvelope.EVENT_TYPE, id, tenantId, Channel.EMAIL, 0, 1,
                DlqReason.MAX_RETRIES_EXCEEDED, "PROVIDER_UNAVAILABLE", 5, clock.instant().toString());
        materialization.apply(event, new RecordCoordinates(
                fixtureTopic, 0, OFFSETS.incrementAndGet(), "{}".getBytes(StandardCharsets.UTF_8)));
        return id;
    }

    /** Runs two callables with a latch so both are inside their transactions before either commits. */
    private List<Outcome> race(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> a = pool.submit(() -> attempt(ready, go, first));
            Future<Outcome> b = pool.submit(() -> attempt(ready, go, second));
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            return List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private Outcome attempt(CountDownLatch ready, CountDownLatch go, Callable<?> action) throws Exception {
        ready.countDown();
        go.await(30, TimeUnit.SECONDS);
        try {
            action.call();
            return new Outcome(true, null);
        } catch (RuntimeException e) {
            return new Outcome(false, e.getClass().getSimpleName());
        }
    }

    private record Outcome(boolean succeeded, String failure) {
    }

    @Test
    @DisplayName("two concurrent replays produce exactly one success and one refusal")
    void concurrentReplays() throws Exception {
        UUID id = materializedEntry();
        UUID entryId = entries.findByNotificationId(id).orElseThrow().getId();

        List<Outcome> outcomes = race(
                () -> adminDlq.replay(entryId, "A", ACTOR_A),
                () -> adminDlq.replay(entryId, "B", ACTOR_B));

        assertThat(outcomes.stream().filter(Outcome::succeeded).count())
                .as("exactly one winner, decided by the conditional predicates")
                .isEqualTo(1);
        DeadLetterEntryEntity entry = entries.findById(entryId).orElseThrow();
        assertThat(entry.getStatus()).isEqualTo(DlqStatus.REPLAYED);
        assertThat(entry.getReplayCount()).as("one replay happened, so one increment").isEqualTo(1);
        assertThat(((Number) jdbc.queryForMap(
                "SELECT delivery_cycle FROM notifications WHERE id = ?", id).get("delivery_cycle")).intValue())
                .as("the cycle advanced exactly once")
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE tenant_id = ?", Long.class, tenantId))
                .as("exactly one audit row, for the action that committed")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'NOTIFICATION_REPLAYED'",
                Long.class, id))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("replay versus discard produces exactly one winner, and the other writes nothing")
    void replayVersusDiscard() throws Exception {
        UUID id = materializedEntry();
        UUID entryId = entries.findByNotificationId(id).orElseThrow().getId();

        List<Outcome> outcomes = race(
                () -> adminDlq.replay(entryId, "replay", ACTOR_A),
                () -> adminDlq.discard(entryId, "discard", ACTOR_B));

        assertThat(outcomes.stream().filter(Outcome::succeeded).count()).isEqualTo(1);
        DeadLetterEntryEntity entry = entries.findById(entryId).orElseThrow();
        Map<String, Object> notification = jdbc.queryForMap(
                "SELECT status, delivery_cycle FROM notifications WHERE id = ?", id);

        if (entry.getStatus() == DlqStatus.REPLAYED) {
            assertThat(notification.get("status")).isEqualTo("QUEUED");
            assertThat(((Number) notification.get("delivery_cycle")).intValue()).isEqualTo(2);
            assertThat(entry.getReplayCount()).isEqualTo(1);
        } else {
            assertThat(entry.getStatus()).isEqualTo(DlqStatus.DISCARDED);
            assertThat(notification.get("status")).as("a discard changes no lifecycle").isEqualTo("DLQ");
            assertThat(((Number) notification.get("delivery_cycle")).intValue()).isEqualTo(1);
            assertThat(entry.getReplayCount()).isZero();
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE tenant_id = ?", Long.class, tenantId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("replay versus tenant revocation: one of two defined outcomes, no partial state")
    void replayVersusTenantRevocation() throws Exception {
        UUID id = materializedEntry();
        UUID entryId = entries.findByNotificationId(id).orElseThrow().getId();

        List<Outcome> outcomes = race(
                () -> adminDlq.replay(entryId, "replay", ACTOR_A),
                () -> transactionTemplate.execute(status -> jdbc.update(
                        "UPDATE tenants SET status = ?, updated_at = now() WHERE id = ?",
                        TenantStatus.REVOKED.name(), tenantId)));

        // The revocation always commits; the replay either saw ACTIVE first or refused.
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenantId))
                .isEqualTo("REVOKED");
        assertThat(outcomes.stream().noneMatch(outcome -> "DeadlockLoserDataAccessException"
                .equals(outcome.failure())))
                .as("the fixed lock order means neither path deadlocks")
                .isTrue();

        DeadLetterEntryEntity entry = entries.findById(entryId).orElseThrow();
        Map<String, Object> notification = jdbc.queryForMap(
                "SELECT status, delivery_cycle, dispatch_generation FROM notifications WHERE id = ?", id);
        long auditRows = jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE tenant_id = ?", Long.class, tenantId);

        if (entry.getStatus() == DlqStatus.REPLAYED) {
            // Outcome 1: the replay committed first. The worker gate will fail it again, which is the documented
            // consequence - not a partial state.
            assertThat(notification.get("status")).isEqualTo("QUEUED");
            assertThat(((Number) notification.get("delivery_cycle")).intValue()).isEqualTo(2);
            assertThat(((Number) notification.get("dispatch_generation")).intValue()).isEqualTo(1);
            assertThat(entry.getReplayCount()).isEqualTo(1);
            assertThat(auditRows).isEqualTo(1);
        } else {
            // Outcome 2: the revocation committed first, so eligibility was read as non-ACTIVE and nothing was
            // written at all.
            assertThat(entry.getStatus()).isEqualTo(DlqStatus.OPEN);
            assertThat(entry.getReplayCount()).isZero();
            assertThat(notification.get("status")).isEqualTo("DLQ");
            assertThat(((Number) notification.get("delivery_cycle")).intValue()).isEqualTo(1);
            assertThat(auditRows).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? "
                    + "AND event_type = 'NOTIFICATION_REPLAYED'", Long.class, id)).isZero();
        }
    }
}
