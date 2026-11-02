package com.notify.retention;

import com.notify.AbstractIsolatedDatabaseTest;
import com.notify.retention.service.DeadLetterEntryRetentionService;
import com.notify.retention.service.DemoWebhookReceiptRetentionService;
import com.notify.retention.service.InAppMessageRetentionService;
import com.notify.retention.service.NotificationRetentionService;
import com.notify.retention.service.RefreshTokenRetentionService;
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
 * / -3: the retention sweeps, with the emphasis on what they must <b>not</b> delete.
 *
 * <p>Retention is switched on for this suite only; the {@code test} profile disables it so other suites keep
 * their fixtures. The row cap is lowered to 2 so the demo-receipt cap is reachable without seeding thousands
 * of rows.
 *
 * <p>Extends {@link AbstractIsolatedDatabaseTest} because these deletes are global by nature: on the shared
 * containers a sweep would reach into other suites' tables. Rows seeded by sibling suites in this hierarchy
 * are recent, so no cutoff here can match them.
 */
@TestPropertySource(properties = {
        "notify.retention.enabled=true",
        "notify.retention.demo-webhook-receipt-max-rows=2",
        // Sweeps are invoked directly; no cron, worker, relay or reaper may race the assertions.
        "notify.retention.refresh-tokens-cron=0 0 5 31 2 *",
        "notify.retention.demo-webhook-receipts-cron=0 0 5 31 2 *",
        "notify.retention.inapp-messages-cron=0 0 5 31 2 *",
        "notify.retention.notifications-cron=0 0 5 31 2 *",
        "notify.retention.dead-letter-entries-cron=0 0 5 31 2 *",
        "notify.worker.email.enabled=false",
        "notify.worker.webhook.enabled=false",
        "notify.worker.inapp.enabled=false",
        "notify.outbox.relay-enabled=false",
        "notify.reconciler.enabled=false",
        "notify.recovery.enabled=false"
})
class RetentionJobsIntegrationTest extends AbstractIsolatedDatabaseTest {

    @Autowired private RefreshTokenRetentionService refreshTokens;
    @Autowired private DemoWebhookReceiptRetentionService demoReceipts;
    @Autowired private InAppMessageRetentionService inappMessages;
    @Autowired private NotificationRetentionService notifications;
    @Autowired private DeadLetterEntryRetentionService deadLetters;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;

    private static Timestamp daysAgo(long days) {
        return Timestamp.from(Instant.now().minus(Duration.ofDays(days)));
    }

    @BeforeEach
    void setUp() {
        tenantId = tenantRepository.save(
                new TenantEntity("RetentionOrg", "retention-org-" + UUID.randomUUID())).getId();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    private UUID seedNotification(String status, Timestamp completedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body, sender,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, deferral_count,
                    completed_at, created_at, updated_at)
                VALUES (?, ?, 'EMAIL', ?, 'r@example.com', 'subject', 'body', 'noreply@notify.local',
                    0, 1, 1, 5, 0, ?, ?, ?)
                """, id, tenantId, status, completedAt, completedAt, completedAt);
        return id;
    }

    private UUID seedRefreshToken(Timestamp expiresAt, Timestamp revokedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO refresh_tokens (id, principal_type, principal_id, token_hash, family_id,
                    expires_at, revoked_at)
                VALUES (?, 'TENANT_USER', ?, ?, ?, ?, ?)
                """, id, UUID.randomUUID(), "hash-" + id, UUID.randomUUID(), expiresAt, revokedAt);
        return id;
    }

    private UUID seedDemoReceipt(Timestamp receivedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO demo_webhook_receipts (id, tenant_id, received_at) VALUES (?, ?, ?)",
                id, tenantId, receivedAt);
        return id;
    }

    private void seedInAppMessage(UUID notificationId, Timestamp createdAt) {
        jdbc.update("""
                INSERT INTO inapp_messages (tenant_id, notification_id, recipient_ref, subject, body, created_at)
                VALUES (?, ?, 'recipient-ref', 'subject', 'body', ?)
                """, tenantId, notificationId, createdAt);
    }

    private void seedDeadLetter(UUID notificationId, String status, Timestamp resolvedAt) {
        jdbc.update("""
                INSERT INTO dead_letter_entries (tenant_id, notification_id, channel, reason, attempt_count,
                    status, resolved_at)
                VALUES (?, ?, 'EMAIL', 'MAX_RETRIES_EXCEEDED', 5, ?, ?)
                """, tenantId, notificationId, status, resolvedAt);
    }

    private boolean notificationExists(UUID id) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE id = ?", Long.class, id);
        return count != null && count > 0;
    }

    @Test
    @DisplayName("refresh tokens: spent rows past 30 days go, live ones stay")
    void refreshTokenSweep() {
        UUID expiredLongAgo = seedRefreshToken(daysAgo(60), null);
        UUID revokedLongAgo = seedRefreshToken(daysAgo(1), daysAgo(60));
        UUID live = seedRefreshToken(Timestamp.from(Instant.now().plus(Duration.ofDays(7))), null);
        UUID recentlyExpired = seedRefreshToken(daysAgo(2), null);

        refreshTokens.sweep();

        assertThat(tokenExists(expiredLongAgo)).as("expired 60 days ago").isFalse();
        assertThat(tokenExists(revokedLongAgo)).as("revoked 60 days ago").isFalse();
        assertThat(tokenExists(live)).as("a live token must survive").isTrue();
        assertThat(tokenExists(recentlyExpired))
                .as("expired only 2 days ago, inside the 30-day window")
                .isTrue();
    }

    private boolean tokenExists(UUID id) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE id = ?", Long.class, id);
        return count != null && count > 0;
    }

    @Test
    @DisplayName("demo receipts: older than 7 days go, and the row cap trims the rest")
    void demoReceiptSweep() {
        UUID old = seedDemoReceipt(daysAgo(30));
        UUID recent1 = seedDemoReceipt(daysAgo(1));
        UUID recent2 = seedDemoReceipt(Timestamp.from(Instant.now()));

        demoReceipts.sweepByAge();
        assertThat(receiptExists(old)).as("received 30 days ago").isFalse();
        assertThat(receiptExists(recent1)).isTrue();
        assertThat(receiptExists(recent2)).isTrue();

        // Cap is 2 for this suite: a third recent receipt pushes the oldest out.
        seedDemoReceipt(Timestamp.from(Instant.now()));
        demoReceipts.sweepByRowCap();

        Long remaining = jdbc.queryForObject(
                "SELECT count(*) FROM demo_webhook_receipts WHERE tenant_id = ?", Long.class, tenantId);
        assertThat(remaining).as("the cap keeps only the newest rows").isLessThanOrEqualTo(2L);
    }

    private boolean receiptExists(UUID id) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM demo_webhook_receipts WHERE id = ?", Long.class, id);
        return count != null && count > 0;
    }

    @Test
    @DisplayName("in-app messages: older than 90 days go, recent ones stay")
    void inAppMessageSweep() {
        UUID oldNotification = seedNotification("DELIVERED", daysAgo(200));
        UUID recentNotification = seedNotification("DELIVERED", Timestamp.from(Instant.now()));
        seedInAppMessage(oldNotification, daysAgo(200));
        seedInAppMessage(recentNotification, Timestamp.from(Instant.now()));

        inappMessages.sweep();

        Long oldRows = jdbc.queryForObject(
                "SELECT count(*) FROM inapp_messages WHERE notification_id = ?", Long.class, oldNotification);
        Long recentRows = jdbc.queryForObject(
                "SELECT count(*) FROM inapp_messages WHERE notification_id = ?", Long.class, recentNotification);
        assertThat(oldRows).isZero();
        assertThat(recentRows).isEqualTo(1L);
    }

    @Test
    @DisplayName("notifications: old terminal rows and their children go; DLQ status and recent rows stay")
    void notificationSweep() {
        UUID oldDelivered = seedNotification("DELIVERED", daysAgo(200));
        UUID oldFailed = seedNotification("FAILED", daysAgo(200));
        UUID oldCancelled = seedNotification("CANCELLED", daysAgo(200));
        UUID oldInDlqStatus = seedNotification("DLQ", daysAgo(200));
        UUID recentDelivered = seedNotification("DELIVERED", Timestamp.from(Instant.now()));

        // A child row that must disappear with its parent through the composite cascade.
        seedInAppMessage(oldDelivered, daysAgo(200));

        notifications.sweep();

        assertThat(notificationExists(oldDelivered)).as("DELIVERED 200 days ago").isFalse();
        assertThat(notificationExists(oldFailed)).as("FAILED 200 days ago").isFalse();
        assertThat(notificationExists(oldCancelled)).as("CANCELLED 200 days ago").isFalse();
        assertThat(notificationExists(recentDelivered)).as("completed today").isTrue();
        assertThat(notificationExists(oldInDlqStatus))
                .as("DLQ is not a retention-eligible terminal status; dead_letter_entries owns that window")
                .isTrue();

        Long orphanChildren = jdbc.queryForObject(
                "SELECT count(*) FROM inapp_messages WHERE notification_id = ?", Long.class, oldDelivered);
        assertThat(orphanChildren).as("children go with the parent, leaving nothing orphaned").isZero();
    }

    @Test
    @DisplayName("notifications: a row linked to an OPEN dead-letter entry is never swept")
    void notificationLinkedToOpenDeadLetterIsExempt() {
        UUID oldButOpenDlq = seedNotification("DELIVERED", daysAgo(200));
        seedDeadLetter(oldButOpenDlq, "OPEN", null);

        notifications.sweep();

        assertThat(notificationExists(oldButOpenDlq))
                .as("deleting it would cascade away an OPEN dead-letter entry an operator has not resolved")
                .isTrue();

        Long openEntries = jdbc.queryForObject(
                "SELECT count(*) FROM dead_letter_entries WHERE notification_id = ? AND status = 'OPEN'",
                Long.class, oldButOpenDlq);
        assertThat(openEntries).as("the open entry itself survives").isEqualTo(1L);
    }

    @Test
    @DisplayName("dead-letter entries: resolved rows past 180 days go, OPEN rows never do")
    void deadLetterSweep() {
        UUID replayedNotification = seedNotification("DELIVERED", Timestamp.from(Instant.now()));
        UUID discardedNotification = seedNotification("FAILED", Timestamp.from(Instant.now()));
        UUID openNotification = seedNotification("DLQ", Timestamp.from(Instant.now()));
        UUID recentlyResolvedNotification = seedNotification("DELIVERED", Timestamp.from(Instant.now()));

        seedDeadLetter(replayedNotification, "REPLAYED", daysAgo(400));
        seedDeadLetter(discardedNotification, "DISCARDED", daysAgo(400));
        seedDeadLetter(openNotification, "OPEN", null);
        seedDeadLetter(recentlyResolvedNotification, "DISCARDED", daysAgo(5));

        deadLetters.sweep();

        assertThat(deadLetterExists(replayedNotification)).as("REPLAYED 400 days ago").isFalse();
        assertThat(deadLetterExists(discardedNotification)).as("DISCARDED 400 days ago").isFalse();
        assertThat(deadLetterExists(openNotification))
                .as("an OPEN entry is unfinished operator work and is kept regardless of age")
                .isTrue();
        assertThat(deadLetterExists(recentlyResolvedNotification))
                .as("resolved 5 days ago, inside the 180-day window")
                .isTrue();
    }

    private boolean deadLetterExists(UUID notificationId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM dead_letter_entries WHERE notification_id = ?", Long.class, notificationId);
        return count != null && count > 0;
    }
}
