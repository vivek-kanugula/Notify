package com.notify.delivery.recovery;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.delivery.model.FencedOutException;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.delivery.service.DeliveryResultService;
import com.notify.notification.dto.ClaimResult;
import com.notify.notification.dto.ClaimSnapshot;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reaper against a slow original worker (/ , ; , , ).
 *
 * <p><b>The claim being tested.</b> The reaper performs <b>no</b> {@code dispatch_generation} bump (). It does
 * not need one: every result write - {@code completeDelivery}, {@code retryDelivery}, {@code failDelivery},
 * {@code deferDelivery} - already requires {@code status = PROCESSING} together with a matching generation and
 * {@code claim_seq}. Moving the row out of PROCESSING is therefore the fence. This test proves it with the real
 * {@link DeliveryResultService}, not by reasoning about the predicates: the original worker's result arrives
 * <em>after</em> the reap and must be rejected in full, leaving no attempt row, no history row and no inbox row.
 */
class ReaperOwnershipRaceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private NotificationService notificationService;
    @Autowired private NotificationTransitioner transitioner;
    @Autowired private DeliveryResultService resultService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private StuckProcessingReaper reaper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = tenantRepository.save(
                new TenantEntity("RaceOrg", "race-org-" + UUID.randomUUID())).getId();
    }

    private UUID seedQueued(Channel channel, String recipient, int maxAttempts) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, ?, 'QUEUED', ?, 'Subject', 'Body', 0, 1, 0, ?, now())
                """, id, tenantId, channel.name(), recipient, maxAttempts);
        return id;
    }

    /** Claims the notification exactly as a worker would, then back-dates the claim so it looks abandoned. */
    private ClaimSnapshot claimAndAbandon(UUID notificationId) {
        ClaimResult result = transitioner.claim(tenantId, notificationId, 0, null);
        assertThat(result).isInstanceOf(ClaimResult.Claimed.class);
        jdbc.update("UPDATE notifications SET processing_started_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(30))), notificationId);
        return ((ClaimResult.Claimed) result).snapshot();
    }

    private ReapOutcome reap(ClaimSnapshot claim) {
        return notificationService.recoverStuckProcessing(
                tenantId, claim.id(), claim.generation(), claim.claimSeq(),
                Instant.now().minus(Duration.ofMinutes(5)));
    }

    private PersistableOutcome.Delivered delivered(ClaimSnapshot claim, String provider) {
        Instant now = clock.instant();
        return new PersistableOutcome.Delivered(now.minusMillis(5), now, provider, claim.id().toString());
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private int historyCount(UUID id) {
        return (int) count("SELECT count(*) FROM notification_status_history WHERE notification_id = ?", id);
    }

    @Test
    @DisplayName("a slow worker's success after a reschedule-reap is fenced out and writes nothing")
    void aLateSuccessAfterARescheduleIsFenced() {
        UUID id = seedQueued(Channel.EMAIL, "someone@example.com", 5);
        ClaimSnapshot claim = claimAndAbandon(id);

        assertThat(reap(claim)).isEqualTo(ReapOutcome.RESCHEDULED);
        int historyAfterReap = historyCount(id);

        // The original worker finally comes back with a success for the claim it still believes it owns.
        assertThatThrownBy(() -> resultService.persist(claim, delivered(claim, "ses")))
                .isInstanceOf(FencedOutException.class);

        assertThat(statusOf(id)).as("the reaped row keeps its own progress").isEqualTo("RETRYING");
        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", id))
                .as("no attempt row from a fenced-out result").isZero();
        assertThat(historyCount(id)).as("no history row either").isEqualTo(historyAfterReap);
        assertThat(jdbc.queryForObject(
                "SELECT provider_message_id FROM notifications WHERE id = ?", String.class, id))
                .as("and no provider message id is recorded for an attempt that never committed")
                .isNull();
    }

    @Test
    @DisplayName("a slow worker's result after a reap-to-FAILED cannot reopen or overwrite the terminal row")
    void aLateResultAfterExhaustionCannotReopenIt() {
        UUID id = seedQueued(Channel.EMAIL, "someone@example.com", 1);   // one attempt, spent by the claim
        ClaimSnapshot claim = claimAndAbandon(id);

        assertThat(reap(claim)).isEqualTo(ReapOutcome.EXHAUSTED);
        assertThat(statusOf(id)).isEqualTo("FAILED");
        long deadLettersAfterReap = count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", id);
        int historyAfterReap = historyCount(id);

        assertThatThrownBy(() -> resultService.persist(claim, delivered(claim, "ses")))
                .isInstanceOf(FencedOutException.class);

        assertThat(statusOf(id)).as("terminal stays terminal").isEqualTo("FAILED");
        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", id)).isZero();
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", id))
                .as("no second dead letter").isEqualTo(deadLettersAfterReap);
        assertThat(historyCount(id)).isEqualTo(historyAfterReap);
    }

    @Test
    @DisplayName("a slow worker's retry and failure results are fenced out for the same reason")
    void lateRetryAndFailureResultsAreAlsoFenced() {
        UUID retried = seedQueued(Channel.EMAIL, "a@example.com", 5);
        ClaimSnapshot retriedClaim = claimAndAbandon(retried);
        assertThat(reap(retriedClaim)).isEqualTo(ReapOutcome.RESCHEDULED);
        Instant now = clock.instant();
        assertThatThrownBy(() -> resultService.persist(retriedClaim, new PersistableOutcome.Retry(
                now.minusMillis(5), now, "ses", "PROVIDER_UNAVAILABLE", "late", null, null,
                now.plusSeconds(60))))
                .isInstanceOf(FencedOutException.class);
        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", retried)).isZero();

        UUID failed = seedQueued(Channel.EMAIL, "b@example.com", 5);
        ClaimSnapshot failedClaim = claimAndAbandon(failed);
        assertThat(reap(failedClaim)).isEqualTo(ReapOutcome.RESCHEDULED);
        assertThatThrownBy(() -> resultService.persist(failedClaim, new PersistableOutcome.Fail(
                now.minusMillis(5), now, "ses", com.notify.common.model.AttemptOutcome.PERMANENT_FAILURE,
                com.notify.common.model.DlqReason.PERMANENT_FAILURE, "BOUNCED", "late", null, null)))
                .isInstanceOf(FencedOutException.class);
        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", failed)).isZero();
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", failed))
                .as("a fenced-out failure writes no dead letter either").isZero();
    }

    @Test
    @DisplayName("a reaped IN_APP notification's late success writes no inbox row")
    void aLateInAppSuccessWritesNoInboxRow() {
        UUID id = seedQueued(Channel.IN_APP, "inbox-user", 5);
        ClaimSnapshot claim = claimAndAbandon(id);

        assertThat(reap(claim)).isEqualTo(ReapOutcome.RESCHEDULED);

        assertThatThrownBy(() -> resultService.persist(claim, delivered(claim, "inapp")))
                .isInstanceOf(FencedOutException.class);

        assertThat(count("SELECT count(*) FROM inapp_messages WHERE notification_id = ?", id))
                .as("holds through recovery: the fenced result rolls its inbox row back with it")
                .isZero();
    }

    @Test
    @DisplayName("the scan finds stuck claims, is bounded, and reports what it did")
    void theScanIsBoundedAndReportsItsWork() {
        UUID recoverable = seedQueued(Channel.EMAIL, "c@example.com", 5);
        UUID exhausted = seedQueued(Channel.EMAIL, "d@example.com", 1);
        UUID young = seedQueued(Channel.EMAIL, "e@example.com", 5);
        claimAndAbandon(recoverable);
        claimAndAbandon(exhausted);
        transitioner.claim(tenantId, young, 0, null);          // claimed, but not back-dated

        StuckProcessingReaper.ScanReport report = reaper.scan();

        // notify.recovery.enabled is false in the test profile, so the scan must do nothing at all.
        assertThat(report.examined()).as("disabled by default: the scan is inert").isZero();
        assertThat(report.reaped()).isZero();
        assertThat(statusOf(recoverable)).isEqualTo("PROCESSING");
        assertThat(statusOf(exhausted)).isEqualTo("PROCESSING");
        assertThat(statusOf(young)).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("every result-write path the worker can take is fenced by the status predicate alone")
    void everyResultPathIsFencedWithoutAGenerationBump() {
        UUID id = seedQueued(Channel.EMAIL, "f@example.com", 5);
        ClaimSnapshot claim = claimAndAbandon(id);
        int generationBefore = jdbc.queryForObject(
                "SELECT dispatch_generation FROM notifications WHERE id = ?", Integer.class, id);

        assertThat(reap(claim)).isEqualTo(ReapOutcome.RESCHEDULED);

        assertThat(jdbc.queryForObject("SELECT dispatch_generation FROM notifications WHERE id = ?", Integer.class, id))
                .as("the generation is untouched, so the fence is the status predicate")
                .isEqualTo(generationBefore);
        // All four conditional updates the worker could still attempt, driven directly.
        assertThat(transitioner.completeDelivery(tenantId, id, claim.generation(), claim.claimSeq(), "ses", "m1"))
                .isFalse();
        assertThat(transitioner.retryDelivery(tenantId, id, claim.generation(), claim.claimSeq(),
                clock.instant().plusSeconds(60), "LATE")).isFalse();
        assertThat(transitioner.failDelivery(tenantId, id, claim.generation(), claim.claimSeq(), "LATE")).isFalse();
        assertThat(transitioner.deferDelivery(tenantId, id, claim.generation(), claim.claimSeq(),
                clock.instant().plusSeconds(60), "LATE")).isFalse();

        assertThat(statusOf(id)).isEqualTo("RETRYING");
        List<Map<String, Object>> attempts = jdbc.queryForList(
                "SELECT id FROM delivery_attempts WHERE notification_id = ?", id);
        assertThat(attempts).isEmpty();
    }
}
