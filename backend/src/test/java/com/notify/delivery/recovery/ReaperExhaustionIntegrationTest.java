package com.notify.delivery.recovery;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.notification.model.NotificationTransitioner;
import com.notify.notification.model.NotificationTransitioner.ReapOutcome;
import com.notify.notification.service.NotificationService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reaper's two terminal paths, and the exhaustion case an unconditional PROCESSING to RETRYING would have got
 * wrong (/ , ; , , , ).
 *
 * <p><b>Why the exhausted path exists.</b> A claim increments {@code attempt_count} before the provider is called.
 * If the worker then dies with the budget already spent, moving the row back to RETRYING would let the due path
 * claim it again and send <b>past {@code max_attempts}</b>. The attempt-budget test therefore lives inside each
 * conditional update, so the choice of path is atomic with the transition.
 *
 * <p>Real PostgreSQL, because every property under test is a property of conditional updates and transactions.
 */
class ReaperExhaustionIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private NotificationService notificationService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = tenantRepository.save(
                new TenantEntity("ReapOrg", "reap-org-" + UUID.randomUUID())).getId();
    }

    /** A notification already in PROCESSING, as a claim would have left it, with the stated budget state. */
    private UUID seedProcessing(int attemptCount, int maxAttempts, Duration claimedAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, claim_seq,
                    queued_at, processing_started_at, first_processing_at)
                VALUES (?, ?, 'EMAIL', 'PROCESSING', 'someone@example.com', 'Subject', 'Body',
                    0, 1, ?, ?, 1, now(), ?, ?)
                """, id, tenantId, attemptCount, maxAttempts,
                java.sql.Timestamp.from(Instant.now().minus(claimedAgo)),
                java.sql.Timestamp.from(Instant.now().minus(claimedAgo)));
        return id;
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private List<Map<String, Object>> history(UUID id) {
        return jdbc.queryForList(
                "SELECT from_status, to_status, reason FROM notification_status_history "
                        + "WHERE notification_id = ? ORDER BY occurred_at, id", id);
    }

    private ReapOutcome reap(UUID id) {
        return notificationService.recoverStuckProcessing(
                tenantId, id, 0, 1, Instant.now().minus(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("maxAttempts = 1: a crashed only attempt reaps straight to FAILED, never to RETRYING")
    void aSingleAttemptBudgetReapsStraightToFailed() {
        UUID id = seedProcessing(1, 1, Duration.ofMinutes(30));

        assertThat(reap(id)).isEqualTo(ReapOutcome.EXHAUSTED);

        assertThat(statusOf(id)).as("never RETRYING: another send must be impossible").isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT last_error_code FROM notifications WHERE id = ?", String.class, id))
                .isEqualTo(StuckProcessingReaper.WORKER_LOST);
        assertThat(jdbc.queryForObject(
                "SELECT next_retry_at FROM notifications WHERE id = ?", java.sql.Timestamp.class, id))
                .as("nothing is left due").isNull();
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", id))
                .as("exactly one durable dead-letter event").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", id))
                .as("no attempt row: no provider outcome was ever observed").isZero();
        assertThat(history(id)).hasSize(1);
        assertThat(history(id).get(0)).containsEntry("from_status", "PROCESSING")
                .containsEntry("to_status", "FAILED");
        assertThat(String.valueOf(history(id).get(0).get("reason")))
                .as("the history says the outcome was never observed, rather than inventing one")
                .contains("budget spent");
    }

    @Test
    @DisplayName("budget remaining: the reap reschedules and the existing retry path takes it from there")
    void budgetRemainingReschedules() {
        UUID id = seedProcessing(2, 5, Duration.ofMinutes(30));

        assertThat(reap(id)).isEqualTo(ReapOutcome.RESCHEDULED);

        assertThat(statusOf(id)).isEqualTo("RETRYING");
        assertThat(jdbc.queryForObject(
                "SELECT next_retry_at FROM notifications WHERE id = ?", java.sql.Timestamp.class, id))
                .as("due immediately, for the existing due path to release").isNotNull();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM notifications WHERE id = ?", Integer.class, id))
                .as("the lost claim's attempt is not refunded").isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT dispatch_generation FROM notifications WHERE id = ?", Integer.class, id))
                .as("no generation bump at reap time").isZero();
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", id))
                .as("no dead letter for a recoverable claim").isZero();
        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", id)).isZero();
    }

    @Test
    @DisplayName("repeated crashes walk the budget down and terminate instead of looping")
    void repeatedCrashesTerminate() {
        UUID id = seedProcessing(1, 3, Duration.ofMinutes(30));
        List<ReapOutcome> outcomes = new ArrayList<>();

        for (int attempt = 1; attempt <= 3; attempt++) {
            outcomes.add(reap(id));
            if (statusOf(id).equals("FAILED")) {
                break;
            }
            // The next crash: the due path would have claimed it, so replay that claim's effect on the row.
            jdbc.update("""
                    UPDATE notifications
                    SET status = 'PROCESSING', attempt_count = attempt_count + 1, claim_seq = 1,
                        processing_started_at = ?
                    WHERE id = ?
                    """, java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(30))), id);
        }

        assertThat(outcomes).containsExactly(
                ReapOutcome.RESCHEDULED, ReapOutcome.RESCHEDULED, ReapOutcome.EXHAUSTED);
        assertThat(statusOf(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM notifications WHERE id = ?", Integer.class, id))
                .as("bounded by max_attempts, never beyond")
                .isEqualTo(3);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent reapers: exactly one FAILED transition, one dead letter, one history row")
    void concurrentReapersActOnce() throws Exception {
        UUID id = seedProcessing(3, 3, Duration.ofMinutes(30));

        int racers = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(racers);
        List<ReapOutcome> outcomes = new java.util.concurrent.CopyOnWriteArrayList<>();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(threads.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    outcomes.add(reap(id));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            threads.shutdownNow();
        }

        assertThat(outcomes.stream().filter(ReapOutcome.EXHAUSTED::equals).count())
                .as("exactly one reaper won").isEqualTo(1);
        assertThat(outcomes.stream().filter(ReapOutcome.NOT_STUCK::equals).count()).isEqualTo(racers - 1);
        assertThat(statusOf(id)).isEqualTo("FAILED");
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", id))
                .as("one dead-letter event, not six").isEqualTo(1);
        assertThat(history(id)).hasSize(1);
        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", id)).isZero();
    }

    @Test
    @DisplayName("a claim younger than the cutoff, or a claim that moved on, is never reaped")
    void notStuckIsLeftAlone() {
        UUID young = seedProcessing(1, 5, Duration.ofSeconds(5));
        assertThat(reap(young)).isEqualTo(ReapOutcome.NOT_STUCK);
        assertThat(statusOf(young)).isEqualTo("PROCESSING");

        // A newer claim: the scan's claim_seq no longer matches, so the reap cannot land on it.
        UUID moved = seedProcessing(2, 5, Duration.ofMinutes(30));
        jdbc.update("UPDATE notifications SET claim_seq = 7 WHERE id = ?", moved);
        assertThat(reap(moved)).isEqualTo(ReapOutcome.NOT_STUCK);
        assertThat(statusOf(moved)).isEqualTo("PROCESSING");

        // A different generation: fenced for the same reason.
        UUID otherGeneration = seedProcessing(2, 5, Duration.ofMinutes(30));
        jdbc.update("UPDATE notifications SET dispatch_generation = 4 WHERE id = ?", otherGeneration);
        assertThat(reap(otherGeneration)).isEqualTo(ReapOutcome.NOT_STUCK);
        assertThat(statusOf(otherGeneration)).isEqualTo("PROCESSING");

        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id IN (?, ?, ?)",
                young, moved, otherGeneration)).isZero();
    }

    @Test
    @DisplayName("the reap leaves the delivery cycle alone, so the provider identity survives recovery")
    void theDeliveryCycleIsUnchanged() {
        UUID id = seedProcessing(1, 5, Duration.ofMinutes(30));
        int cycleBefore = jdbc.queryForObject(
                "SELECT delivery_cycle FROM notifications WHERE id = ?", Integer.class, id);

        assertThat(reap(id)).isEqualTo(ReapOutcome.RESCHEDULED);

        assertThat(jdbc.queryForObject("SELECT delivery_cycle FROM notifications WHERE id = ?", Integer.class, id))
                .as("a reap is not a new cycle, so <notificationId>-<deliveryCycle> is preserved and a "
                        + "recovered send carries the identity the crashed attempt used")
                .isEqualTo(cycleBefore);
    }

    @Test
    @DisplayName("the transitioner reports NOT_STUCK rather than throwing when nothing matches")
    void nothingMatchesIsANormalResult(@Autowired NotificationTransitioner transitioner) {
        UUID absent = UUID.randomUUID();
        assertThat(transitioner.reapStuckProcessing(tenantId, absent, 0, 1,
                Instant.now(), StuckProcessingReaper.WORKER_LOST))
                .isEqualTo(ReapOutcome.NOT_STUCK);
    }
}
