package com.notify.outage;

import com.notify.AbstractIsolatedDatabaseTest;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.provider.EmailSendRequest;
import com.notify.delivery.provider.resend.ResendEmailProvider;
import com.notify.delivery.provider.resend.ResendProperties;
import com.notify.delivery.recovery.StuckProcessingReaper;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * / -2: what happens when a provider call is <b>ambiguous</b> and when a worker dies holding a claim.
 *
 * <p>Two distinct failure shapes:
 * <ul>
 *   <li><b>Provider unreachable.</b> A connection that never completes leaves the remote effect unknown, so it
 *       must be classified retryable, never permanent — a permanent verdict would discard a message that may
 *       yet have been accepted.</li>
 *   <li><b>Worker lost mid-claim.</b> A {@code PROCESSING} row whose owner vanished is recovered by
 *       {@link StuckProcessingReaper}: rescheduled while attempt budget remains, and failed once it is spent.
 *       Each path is chosen atomically by its own conditional update, so a row with no budget left can never
 *       be handed back to the claim path and sent past {@code max_attempts}.</li>
 * </ul>
 *
 * <p>Extends {@link AbstractIsolatedDatabaseTest} because the reaper's scan is deliberately <b>global</b>:
 * on the shared containers another suite's {@code PROCESSING} rows would land inside its results. The test
 * deletes its own tenant afterwards, which cascades to every row it created.
 *
 * <p>{@code stuck-after} is set to the one-minute floor {@code ReaperProperties} permits, and claims are
 * back-dated well past it, so the scan is immediate rather than waiting out a real window.
 */
@TestPropertySource(properties = {
        "notify.recovery.enabled=true",
        "notify.recovery.stuck-after=1m",
        // The reaper is invoked explicitly; no background timer or worker may race these assertions.
        "notify.recovery.scan-interval=1h",
        "notify.worker.email.enabled=false",
        "notify.worker.webhook.enabled=false",
        "notify.worker.inapp.enabled=false",
        "notify.outbox.relay-enabled=false",
        "notify.reconciler.enabled=false"
})
// / -3: this class enables background work (relay, reconciler, reaper or worker). Spring caches
// test contexts for the whole JVM run, so without this the schedulers keep polling after the class finishes -
// and the outbox relay's fetch is global, with no tenant predicate. Dirtying the context shuts those pools
// down at class exit instead of leaking background polling into later suites.
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ProviderOutageReaperRecoveryTest extends AbstractIsolatedDatabaseTest {

    @Autowired private StuckProcessingReaper reaper;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = tenantRepository.save(
                new TenantEntity("ProviderOutageOrg", "provider-outage-" + UUID.randomUUID())).getId();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    /** A PROCESSING row whose claim is older than the stuck cutoff, i.e. its worker never came back. */
    private UUID seedStuckProcessing(int attemptCount, int maxAttempts) {
        UUID id = UUID.randomUUID();
        // java.sql.Timestamp, not Instant: the driver cannot infer a SQL type for Instant parameters.
        Timestamp longAgo = Timestamp.from(Instant.now().minus(Duration.ofMinutes(30)));
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body, sender,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, deferral_count,
                    claim_seq, queued_at, processing_started_at, first_processing_at)
                VALUES (?, ?, 'EMAIL', 'PROCESSING', 'stuck@example.com', 'subject', 'body',
                    'noreply@notify.local', 0, 1, ?, ?, 0, 1, ?, ?, ?)
                """, id, tenantId, attemptCount, maxAttempts, longAgo, longAgo, longAgo);
        return id;
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("an unreachable provider is retryable, never permanent (the remote effect is unknown)")
    void unreachableProviderIsRetryable() {
        // 127.0.0.1:1 has nothing listening, so the exchange cannot complete.
        ResendProperties properties = new ResendProperties(
                "key-TEST-ONLY-000", "http://127.0.0.1:1",
                Duration.ofMillis(300), Duration.ofMillis(500), 65536);
        ResendEmailProvider provider = new ResendEmailProvider(properties);
        try {
            DeliveryResult result = provider.send(new EmailSendRequest(
                    UUID.randomUUID() + "-1", "noreply@notify.local", "someone@example.com",
                    "subject", "body"));

            assertThat(result)
                    .as("an unfinished exchange leaves the remote effect unknown, so it must be retryable")
                    .isInstanceOf(DeliveryResult.RetryableFailure.class);
            assertThat(result)
                    .as("classifying an unreachable provider as permanent would discard a possibly-sent message")
                    .isNotInstanceOf(DeliveryResult.PermanentFailure.class);
            assertThat(((DeliveryResult.RetryableFailure) result).code()).isNotBlank();
        } finally {
            provider.shutdown();
        }
    }

    @Test
    @DisplayName("the reaper reschedules a stuck claim that still has attempt budget")
    void reaperReschedulesClaimWithBudgetLeft() {
        UUID id = seedStuckProcessing(1, 5);

        StuckProcessingReaper.ScanReport report = reaper.scan();

        assertThat(report.examined()).isPositive();
        assertThat(statusOf(id))
                .as("budget remained, so the row goes back to RETRYING for the normal retry path")
                .isEqualTo("RETRYING");

        Timestamp nextRetryAt = jdbc.queryForObject(
                "SELECT next_retry_at FROM notifications WHERE id = ?", Timestamp.class, id);
        assertThat(nextRetryAt)
                .as("a rescheduled claim must be due so the retry path can pick it up")
                .isNotNull();
    }

    @Test
    @DisplayName("the reaper fails a stuck claim whose attempt budget is spent, rather than letting it send again")
    void reaperExhaustsClaimWithoutBudget() {
        UUID id = seedStuckProcessing(5, 5);

        StuckProcessingReaper.ScanReport report = reaper.scan();

        assertThat(report.examined()).isPositive();
        assertThat(statusOf(id))
                .as("no budget left, so the row is FAILED and cannot be claimed past max_attempts")
                .isEqualTo("FAILED");

        String lastErrorCode = jdbc.queryForObject(
                "SELECT last_error_code FROM notifications WHERE id = ?", String.class, id);
        assertThat(lastErrorCode)
                .as("the recorded reason says the outcome was never observed, not an invented verdict")
                .isEqualTo(StuckProcessingReaper.WORKER_LOST);
    }

    @Test
    @DisplayName("a recent PROCESSING claim is left alone")
    void freshClaimIsNotReaped() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body, sender,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, deferral_count,
                    claim_seq, queued_at, processing_started_at, first_processing_at)
                VALUES (?, ?, 'EMAIL', 'PROCESSING', 'fresh@example.com', 'subject', 'body',
                    'noreply@notify.local', 0, 1, 1, 5, 0, 1, now(), now(), now())
                """, id, tenantId);

        reaper.scan();

        assertThat(statusOf(id))
                .as("a claim inside the stuck window belongs to a worker that may still be working")
                .isEqualTo("PROCESSING");
    }
}
