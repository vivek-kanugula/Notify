package com.notify.delivery.resilience;

import com.notify.AbstractIsolatedDatabaseTest;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.delivery.service.DeliveryOrchestrator;
import com.notify.delivery.service.DeliveryResultService;
import com.notify.notification.dto.ClaimResult;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.notification.model.NotificationTransitioner;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import com.notify.webhook.service.WebhookEndpointService;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.Bulkhead;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider resilience <b>switched on</b>, through the real composition (/ , ; repair of review
 * finding F4).
 *
 * <p><b>Why this exists.</b> Every other check is a unit test over hand-built primitives, and no test anywhere
 * set {@code notify.resilience.enabled=true} in a Spring context. So the wiring that decides whether the feature
 * works at all - the guard bean reaching {@code ResendEmailProvider} and {@code WebhookSender}, the qualified
 * bulkheads, the adapters' translation of a refusal, the orchestrator's accounting and the committed result - had
 * never been executed together. This drives both guarded channels end to end against a private database.
 *
 * <p><b>How a refusal is provoked without touching a provider.</b> Each channel bulkhead is configured with a
 * single permit, which the test takes for itself; the guard then refuses the next call before any socket is opened.
 * The EMAIL provider is the real Resend adapter pointed at {@code 127.0.0.1:1} - a port nothing listens on - so an
 * attempted request could not be mistaken for a refusal: it would surface as {@code PROVIDER_UNAVAILABLE} and fail
 * these assertions. The WEBHOOK destination is a loopback receiver that counts requests, and the count must stay
 * zero.
 */
@TestPropertySource(properties = {
        "notify.resilience.enabled=true",
        // One permit per channel, so the test can hold it and make the next call refuse.
        "notify.resilience.bulkhead.email=1",
        "notify.resilience.bulkhead.webhook=1",
        "notify.resilience.breaker.wait-in-open-state=30s",
        "notify.resilience.host-registry.idle-expiry=31s",
        // A small cap so the capped-refusal attribution is reachable with a seeded deferral_count.
        "notify.resilience.max-deferrals=2",
        "notify.resilience.rate-limit.resend-permits=0",
        // The real Resend adapter, aimed at a port nothing listens on.
        "notify.email.provider=resend",
        "notify.resend.api-key=key-TEST-ONLY-000",
        "notify.resend.base-url=http://127.0.0.1:1",
        "notify.mail.from=noreply@resilience.notify.local",
        "notify.webhook.demo-allowlist=127.0.0.1,localhost",
        "notify.retry.base-delay=2s",
        "notify.retry.max-delay=10s",
        // Nothing else may run: no worker, no recovery, no reconciler, no relay.
        "notify.worker.email.enabled=false",
        "notify.worker.webhook.enabled=false",
        "notify.worker.inapp.enabled=false",
        "notify.recovery.enabled=false",
        "notify.reconciler.enabled=false",
        "notify.outbox.relay-enabled=false"
})
class ResilienceEnabledDeliveryIntegrationTest extends AbstractIsolatedDatabaseTest {

    private static final HttpServer receiver;
    private static final int receiverPort;
    private static final AtomicInteger RECEIVED = new AtomicInteger();

    static {
        try {
            receiver = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the loopback receiver", e);
        }
        receiverPort = receiver.getAddress().getPort();
        receiver.createContext("/hook", exchange -> {
            RECEIVED.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        receiver.start();
    }

    private static String receiverUrl() {
        return "http://127.0.0.1:" + receiverPort + "/hook";
    }

    /**
     * The demo receiver URL, which is what lets a loopback {@code http} destination pass the policy ().
     * A different key from the base class's registrations, so nothing overwrites anything: that precedence trap is
     * the one documented in {@link AbstractIsolatedDatabaseTest}.
     */
    @org.springframework.test.context.DynamicPropertySource
    static void demoReceiver(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("notify.demo.webhook-url", ResilienceEnabledDeliveryIntegrationTest::receiverUrl);
    }

    @Autowired private DeliveryOrchestrator orchestrator;
    @Autowired private DeliveryResultService resultService;
    @Autowired private NotificationTransitioner transitioner;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private WebhookEndpointService endpointService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("emailBulkhead") private Bulkhead emailBulkhead;
    @Autowired @Qualifier("webhookBulkhead") private Bulkhead webhookBulkhead;

    private UUID tenantId;

    @AfterAll
    static void stopReceiver() {
        receiver.stop(0);
    }

    @BeforeEach
    void setUp() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class))
                .as("a private database plus fixture-owned cleanup means the table starts empty")
                .isZero();
        RECEIVED.set(0);
        tenantId = tenantRepository.save(
                new TenantEntity("ResilienceOrg", "resilience-org-" + UUID.randomUUID())).getId();
        endpointService.upsert(tenantId, receiverUrl(), true);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    private ClaimSnapshot claim(String channel, int maxAttempts, int deferralCount) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body, sender,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, deferral_count, queued_at)
                VALUES (?, ?, ?, 'QUEUED', ?, 'subject', 'body', ?, 0, 1, 0, ?, ?, now())
                """, id, tenantId, channel,
                "EMAIL".equals(channel) ? "someone@example.com" : "receiver-ref",
                "EMAIL".equals(channel) ? "noreply@resilience.notify.local" : null,
                maxAttempts, deferralCount);
        ClaimResult result = transitioner.claim(tenantId, id, 0, null);
        return ((ClaimResult.Claimed) result).snapshot();
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap(
                "SELECT status, attempt_count, deferral_count, last_error_code, next_retry_at "
                        + "FROM notifications WHERE id = ?", id);
    }

    private long attempts(UUID id) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", Long.class, id);
        return count == null ? 0 : count;
    }

    /**
     * The whole refusal journey for one channel: claim, deliver under a saturated bulkhead, persist the planned
     * outcome, then read the committed row.
     */
    private void assertRefusalIsAttemptFree(String channel, Bulkhead bulkhead) {
        ClaimSnapshot claimed = claim(channel, 5, 0);
        assertThat(row(claimed.id()).get("attempt_count"))
                .as("the claim itself consumed one attempt")
                .isEqualTo(1);

        assertThat(bulkhead.tryAcquirePermission()).as("the test holds the only permit").isTrue();
        PersistableOutcome outcome;
        try {
            outcome = orchestrator.deliver(claimed);
        } finally {
            bulkhead.releasePermission();
        }

        assertThat(outcome)
                .as("a refusal taken before any provider contact is an attempt-free deferral")
                .isInstanceOf(PersistableOutcome.Defer.class);
        PersistableOutcome.Defer defer = (PersistableOutcome.Defer) outcome;
        assertThat(defer.errorCode()).isEqualTo(ProviderCallGuard.BULKHEAD_FULL);

        resultService.persist(claimed, outcome);

        Map<String, Object> persisted = row(claimed.id());
        assertThat(persisted.get("status")).isEqualTo("RETRYING");
        assertThat(((Number) persisted.get("attempt_count")).intValue())
                .as("the claim's attempt is refunded, so the budget is untouched by a call that never happened")
                .isZero();
        assertThat(((Number) persisted.get("deferral_count")).intValue()).isEqualTo(1);
        assertThat(persisted.get("last_error_code")).isEqualTo(ProviderCallGuard.BULKHEAD_FULL);
        assertThat(attempts(claimed.id())).as("no attempt-history row").isZero();
        // The deferral's own due time: the guard asked for breaker.wait-in-open-state (30s here) and the
        // orchestrator floors every deferral at MIN_DEFERRAL, so the gap from the planned finish is the configured
        // wait - not "some time in the future". Measured against the outcome's own finishedAt rather than the
        // clock, so the assertion does not depend on how long the persist took.
        Instant nextRetryAt = ((Timestamp) persisted.get("next_retry_at")).toInstant();
        assertThat(Duration.between(defer.finishedAt(), nextRetryAt))
                .as("a deferral is durably scheduled at the refusal's own wait, floored at MIN_DEFERRAL")
                .isGreaterThanOrEqualTo(Duration.ofSeconds(1))
                // A millisecond of slack only: PostgreSQL keeps microseconds, so the round trip can shave nanos
                // off an otherwise exact 30s, or round it up. The point is that the value is the configured wait.
                .isBetween(Duration.ofMillis(29_999), Duration.ofMillis(30_001));
        assertThat(nextRetryAt).isAfter(defer.startedAt());
    }


    @Test
    @DisplayName("/15.3 an EMAIL guard refusal is attempt-free, durable, and contacts no provider")
    void emailRefusalIsAttemptFreeAndDurable() {
        assertRefusalIsAttemptFree("EMAIL", emailBulkhead);
        // The adapter is the real Resend one, aimed at a closed port: a request would have produced
        // PROVIDER_UNAVAILABLE instead of the deferral asserted above.
    }

    @Test
    @DisplayName("/15.3 a WEBHOOK guard refusal is attempt-free, durable, and opens no socket")
    void webhookRefusalIsAttemptFreeAndDurable() {
        assertRefusalIsAttemptFree("WEBHOOK", webhookBulkhead);
        assertThat(RECEIVED.get()).as("the receiver was never contacted").isZero();
    }

    @Test
    @DisplayName("at the deferral cap the attempt row names notify-system, because nothing was sent")
    void atTheCapTheAttemptRowNamesTheSystem() {
        // deferral_count already at notify.resilience.max-deferrals, so the next deferral becomes a real retry.
        ClaimSnapshot claimed = claim("WEBHOOK", 5, 2);

        assertThat(webhookBulkhead.tryAcquirePermission()).isTrue();
        PersistableOutcome outcome;
        try {
            outcome = orchestrator.deliver(claimed);
        } finally {
            webhookBulkhead.releasePermission();
        }

        assertThat(outcome).isInstanceOf(PersistableOutcome.Retry.class);
        assertThat(((PersistableOutcome.Retry) outcome).provider())
                .isEqualTo(DeliveryOrchestrator.SYSTEM_PROVIDER);

        resultService.persist(claimed, outcome);

        Map<String, Object> attempt = jdbc.queryForMap(
                "SELECT provider, error_code, outcome FROM delivery_attempts WHERE notification_id = ?",
                claimed.id());
        assertThat(attempt.get("provider"))
                .as("no request was sent, so the provider must not be named")
                .isEqualTo(DeliveryOrchestrator.SYSTEM_PROVIDER);
        assertThat(attempt.get("error_code")).isEqualTo(ProviderCallGuard.BULKHEAD_FULL);
        assertThat(((Number) row(claimed.id()).get("attempt_count")).intValue())
                .as("the cap converts the deferral into a real retryable failure, which does consume the attempt")
                .isEqualTo(1);
        assertThat(RECEIVED.get()).isZero();
    }

    @Test
    @DisplayName("with resilience enabled an allowed WEBHOOK call still reaches the receiver and is delivered")
    void anAllowedCallStillDelivers() {
        ClaimSnapshot claimed = claim("WEBHOOK", 5, 0);

        PersistableOutcome outcome = orchestrator.deliver(claimed);

        assertThat(outcome)
                .as("the guard is enabled but nothing refuses: the production path must work")
                .isInstanceOf(PersistableOutcome.Delivered.class);
        resultService.persist(claimed, outcome);

        assertThat(RECEIVED.get()).isEqualTo(1);
        assertThat(row(claimed.id()).get("status")).isEqualTo("DELIVERED");
        assertThat(attempts(claimed.id())).isEqualTo(1);
        assertThat(webhookBulkhead.getMetrics().getAvailableConcurrentCalls())
                .as("the permit is released after a successful send")
                .isEqualTo(1);
    }
}
