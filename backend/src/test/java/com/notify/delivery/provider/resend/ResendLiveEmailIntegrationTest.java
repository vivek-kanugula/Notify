package com.notify.delivery.provider.resend;

import com.notify.AbstractIsolatedDatabaseTest;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.delivery.service.DeliveryOrchestrator;
import com.notify.delivery.service.DeliveryResultService;
import com.notify.notification.dto.ClaimResult;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.notification.model.NotificationTransitioner;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * / (): one real outbound send through the live Resend account, carried all the way to the
 * terminal {@code DELIVERED} status.
 *
 * <p><b>This test sends a real email.</b> Three independent guards keep it out of ordinary runs:
 * <ol>
 *   <li>{@code @Tag("live-email")}, excluded by the Surefire {@code excludedGroups} default in
 *       {@code backend/pom.xml} — so {@code scripts/gate.ps1} and the CI gate never select it;</li>
 *   <li>{@code NOTIFY_LIVE_EMAIL_ENABLED} must equal {@code true};</li>
 *   <li>{@code NOTIFY_RESEND_API_KEY} must be non-empty.</li>
 * </ol>
 * The environment guards are {@code @EnabledIfEnvironmentVariable} rather than in-test assumptions on
 * purpose: {@code ResendEmailProvider} refuses to construct without an API key, so a missing key has to
 * prevent the Spring context from being built at all instead of failing it.
 *
 * <p>Run it deliberately:
 * <pre>
 *   ./mvnw.cmd -Plive-email test
 * </pre>
 *
 * <p>Environment:
 * <ul>
 *   <li>{@code NOTIFY_RESEND_API_KEY} — the live key (never logged; {@code ResendProperties.toString()} redacts it)</li>
 *   <li>{@code NOTIFY_MAIL_FROM} — verified sender domain, also the {@code notify.mail.from} the app snapshots</li>
 *   <li>{@code NOTIFY_LIVE_EMAIL_TO} — recipient whose inbox is checked for receipt</li>
 * </ul>
 *
 * <p><b>Scope note.</b> The path driven here is claim -> {@link DeliveryOrchestrator#deliver} (the real Resend
 * HTTP exchange) -> {@link DeliveryResultService#persist} -> terminal {@code DELIVERED} plus its
 * {@code delivery_attempts} row. The Kafka hop ahead of the claim is deliberately not re-proved with a live
 * key: it is already covered deterministically by the fake-provider suites, and every extra moving part here
 * would mean another real email on each retry. Workers, relay, reconciler and recovery are all switched off so
 * nothing else can pick the row up concurrently.
 */
@Tag("live-email")
@EnabledIfEnvironmentVariable(named = "NOTIFY_LIVE_EMAIL_ENABLED", matches = "true",
        disabledReason = "NOTIFY_LIVE_EMAIL_ENABLED is not true - live email send skipped")
@EnabledIfEnvironmentVariable(named = "NOTIFY_RESEND_API_KEY", matches = ".+",
        disabledReason = "NOTIFY_RESEND_API_KEY is not configured - live email send skipped")
@TestPropertySource(properties = {
        // The real adapter against the real API (notify.resend.base-url keeps its https://api.resend.com default).
        "notify.email.provider=resend",
        "notify.resend.api-key=${NOTIFY_RESEND_API_KEY:}",
        "notify.mail.from=${NOTIFY_MAIL_FROM:}",
        // Nothing else may touch the row: no worker, no relay, no reconciler, no recovery.
        "notify.worker.email.enabled=false",
        "notify.worker.webhook.enabled=false",
        "notify.worker.inapp.enabled=false",
        "notify.outbox.relay-enabled=false",
        "notify.reconciler.enabled=false",
        "notify.recovery.enabled=false"
})
class ResendLiveEmailIntegrationTest extends AbstractIsolatedDatabaseTest {

    @Autowired private DeliveryOrchestrator orchestrator;
    @Autowired private DeliveryResultService resultService;
    @Autowired private NotificationTransitioner transitioner;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = tenantRepository.save(
                new TenantEntity("LiveEmailOrg", "live-email-org-" + UUID.randomUUID())).getId();
    }

    @AfterEach
    void tearDown() {
        // Cascades to every row this test created, as required by AbstractIsolatedDatabaseTest.
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    @Test
    @DisplayName("live Resend send reaches terminal DELIVERED with a provider message id")
    void liveSendReachesDelivered() {
        String from = System.getenv("NOTIFY_MAIL_FROM");
        String to = System.getenv("NOTIFY_LIVE_EMAIL_TO");
        Assumptions.assumeTrue(from != null && !from.isBlank(),
                "NOTIFY_MAIL_FROM (verified sender) is required for a live send");
        Assumptions.assumeTrue(to != null && !to.isBlank(),
                "NOTIFY_LIVE_EMAIL_TO (recipient) is required to verify receipt");

        UUID id = UUID.randomUUID();
        String subject = "Notify live email check " + id;
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body, sender,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, deferral_count, queued_at)
                VALUES (?, ?, 'EMAIL', 'QUEUED', ?, ?, ?, ?, 0, 1, 0, 5, 0, now())
                """,
                id, tenantId, to, subject,
                "Automated live provider check. No action required.", from);

        ClaimResult claimResult = transitioner.claim(tenantId, id, 0, null);
        assertThat(claimResult)
                .as("the seeded QUEUED row must be claimable")
                .isInstanceOf(ClaimResult.Claimed.class);
        ClaimSnapshot claimed = ((ClaimResult.Claimed) claimResult).snapshot();

        PersistableOutcome outcome = orchestrator.deliver(claimed);
        assertThat(outcome)
                .as("live Resend send should be accepted; got %s", outcome.getClass().getSimpleName())
                .isInstanceOf(PersistableOutcome.Delivered.class);

        PersistableOutcome.Delivered delivered = (PersistableOutcome.Delivered) outcome;
        assertThat(delivered.provider()).isEqualTo(ResendEmailProvider.NAME);
        assertThat(delivered.messageId())
                .as("provider message id proves the remote accepted the message")
                .isNotBlank();

        resultService.persist(claimed, outcome);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, provider, provider_message_id, attempt_count, completed_at "
                        + "FROM notifications WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("DELIVERED");
        assertThat(row.get("provider")).isEqualTo(ResendEmailProvider.NAME);
        assertThat((String) row.get("provider_message_id")).isNotBlank();
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(row.get("completed_at")).isNotNull();

        Map<String, Object> attempt = jdbc.queryForMap(
                "SELECT outcome, provider, attempt_number FROM delivery_attempts WHERE notification_id = ?", id);
        assertThat(attempt.get("outcome")).isEqualTo("SUCCESS");
        assertThat(attempt.get("provider")).isEqualTo(ResendEmailProvider.NAME);
        assertThat(((Number) attempt.get("attempt_number")).intValue()).isEqualTo(1);
    }
}
