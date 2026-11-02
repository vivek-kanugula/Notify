package com.notify.delivery.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractKafkaIntegrationTest;
import com.notify.delivery.provider.webhook.WebhookChannelAdapter;
import com.notify.delivery.provider.webhook.WebhookSignature;
import com.notify.quartz.service.NotificationSchedulerService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import com.notify.webhook.service.WebhookEndpointService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The whole webhook journey on disposable services: a row in PostgreSQL, a dispatch record in Kafka, the worker, a
 * real signed HTTP POST to a loopback receiver, and the durable result. The receiver verifies the signature the way a
 * tenant's receiver would, so a signing regression fails here rather than in production.
 *
 * <p>The destination is the configured demo URL, which is the only reason plain {@code http} to a loopback address is
 * accepted (). Nothing outside this JVM is contacted.
 */
@TestPropertySource(properties = {
        "notify.worker.webhook.enabled=true",
        "notify.worker.concurrency.webhook=1",
        "notify.worker.webhook.retry-interval=100ms",
        "notify.worker.webhook.pause-initial=300ms",
        "notify.worker.webhook.pause-max=1s",
        "notify.worker.webhook.health-poll-interval=200ms",
        "notify.retry.base-delay=2s",
        "notify.retry.max-delay=10s",
        "notify.webhook.demo-allowlist=127.0.0.1,localhost",
        "notify.webhook.connect-timeout=500ms",
        "notify.webhook.total-timeout=1s",
        // Inside the shortened attempt budget: the property validation refuses a DNS ceiling larger than the total.
        "notify.webhook.dns-timeout=500ms"
})
class WebhookWorkerIntegrationTest extends AbstractKafkaIntegrationTest {

    static final String TOPIC = WorkerKafkaSupport.uniqueTopic("notify.test.webhook.w1");
    static final String DLT = TOPIC + ".DLT";
    static final String GROUP = "grp-" + TOPIC;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final String HOOK_PATH = "/demo-hook";

    /** What the receiver should do with the next request. */
    private enum Mode { OK, SERVER_ERROR, NOT_FOUND, HANG, OVERSIZE_MULTIBYTE, RETRY_AFTER }

    private static final HttpServer RECEIVER;
    private static final int PORT;
    private static final AtomicReference<Mode> MODE = new AtomicReference<>(Mode.OK);
    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();

    /** One request as the receiver saw it, including whether its signature verified. */
    private record Received(String deliveryId, String attempt, String body, boolean signatureValid) {
    }

    /** Secrets by tenant, so the receiver can verify like a real one without reaching into the database. */
    private static final Map<UUID, String> SECRETS = new ConcurrentHashMap<>();

    static {
        try {
            WorkerKafkaSupport.createTopics(kafka.getBootstrapServers(), 3, TOPIC, DLT);
            RECEIVER = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            PORT = RECEIVER.getAddress().getPort();
            RECEIVER.createContext(HOOK_PATH, exchange -> {
                byte[] body;
                try (InputStream in = exchange.getRequestBody()) {
                    body = in.readAllBytes();
                }
                String deliveryId = exchange.getRequestHeaders().getFirst(WebhookChannelAdapter.HEADER_DELIVERY_ID);
                String attempt = exchange.getRequestHeaders().getFirst(WebhookChannelAdapter.HEADER_ATTEMPT);
                RECEIVED.add(new Received(deliveryId, attempt, new String(body, StandardCharsets.UTF_8),
                        verify(exchange.getRequestHeaders().getFirst(WebhookSignature.HEADER), body)));

                Mode mode = MODE.get();
                if (mode == Mode.HANG) {
                    try {
                        // Longer than the configured total timeout, short enough not to outlive the test class.
                        Thread.sleep(2_500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                int status = switch (mode) {
                    case OK, HANG -> 200;
                    case SERVER_ERROR, RETRY_AFTER -> 503;
                    case NOT_FOUND -> 404;
                    case OVERSIZE_MULTIBYTE -> 500;
                };
                if (mode == Mode.RETRY_AFTER) {
                    exchange.getResponseHeaders().add("Retry-After", "120");
                }
                // A multibyte error page far larger than the read cap: the stored snippet must still be valid UTF-8,
                // inside its byte budget, and visibly truncated.
                byte[] response = mode == Mode.OVERSIZE_MULTIBYTE
                        ? ("☃".repeat(40_000)).getBytes(StandardCharsets.UTF_8)
                        : "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, response.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response);
                }
                exchange.close();
            });
            // A pool, not the default single dispatcher thread: the hanging-receiver case must not block the next
            // test's request and turn it into a timeout too.
            RECEIVER.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
            RECEIVER.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Receiver-side verification: recompute the HMAC over the exact bytes with the tenant's own secret. */
    private static boolean verify(String header, byte[] body) {
        WebhookSignature.Parsed parsed = WebhookSignature.parse(header);
        if (parsed == null) {
            return false;
        }
        return SECRETS.values().stream()
                .anyMatch(secret -> WebhookSignature.matches(
                        WebhookSignature.hex(secret, parsed.timestamp(), body), parsed.v1()));
    }

    private static String demoUrl() {
        return "http://127.0.0.1:" + PORT + HOOK_PATH;
    }

    @DynamicPropertySource
    static void workerProperties(DynamicPropertyRegistry registry) {
        registry.add("notify.worker.webhook.topic", () -> TOPIC);
        registry.add("notify.worker.webhook.group-id", () -> GROUP);
        registry.add("notify.demo.webhook-url", WebhookWorkerIntegrationTest::demoUrl);
    }

    @Autowired private WebhookEndpointService endpointService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationSchedulerService scheduler;
    @Autowired private com.notify.notification.model.NotificationTransitioner transitioner;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        RECEIVED.clear();
        MODE.set(Mode.OK);
        tenantId = tenantRepository.save(new TenantEntity("WebhookOrg", "webhook-org-" + UUID.randomUUID())).getId();
        SECRETS.put(tenantId, endpointService.upsert(tenantId, demoUrl(), null).secretOnce());
    }

    private String bootstrap() {
        return kafka.getBootstrapServers();
    }

    /** A QUEUED WEBHOOK notification whose recipient is the tenant's destination. */
    private UUID seedQueued(int generation, int maxAttempts) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body, metadata,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, trace_context, queued_at)
                VALUES (?, ?, 'WEBHOOK', 'QUEUED', ?, 'Order shipped', 'Your order is on its way',
                    '{"orderId":"A-1"}'::jsonb, ?, 1, 0, ?, NULL, now())
                """, id, tenantId, demoUrl(), generation, maxAttempts);
        return id;
    }

    private void publish(UUID notificationId, int generation) throws Exception {
        WorkerKafkaSupport.publish(bootstrap(), TOPIC, notificationId.toString(),
                WorkerKafkaSupport.dispatchEvent(tenantId, notificationId, generation, "NOTIFICATION_QUEUED", "WEBHOOK"));
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id);
    }

    private void awaitStatus(UUID id, String expected) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(status(id)).isEqualTo(expected));
    }

    private void awaitAllCommitted() {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(WorkerKafkaSupport.uncommitted(bootstrap(), GROUP, TOPIC)).isZero());
    }

    private List<Map<String, Object>> attempts(UUID id) {
        return jdbc.queryForList(
                "SELECT * FROM delivery_attempts WHERE notification_id = ? ORDER BY attempt_number", id);
    }

    @Test
    @DisplayName("a queued webhook notification is signed, posted to the destination and recorded DELIVERED")
    void deliveredEndToEnd() throws Exception {
        UUID id = seedQueued(0, 5);

        publish(id, 0);
        awaitStatus(id, "DELIVERED");
        awaitAllCommitted();

        assertThat(RECEIVED).hasSize(1);
        Received received = RECEIVED.get(0);
        assertThat(received.signatureValid()).as("the receiver verified the signature itself").isTrue();
        assertThat(received.deliveryId()).isEqualTo(id + "-1");
        assertThat(received.attempt()).isEqualTo("1");

        JsonNode payload = MAPPER.readTree(received.body());
        assertThat(payload.get("schemaVersion").intValue()).isEqualTo(1);
        assertThat(payload.get("notificationId").textValue()).isEqualTo(id.toString());
        assertThat(payload.get("channel").textValue()).isEqualTo("WEBHOOK");
        assertThat(payload.get("subject").textValue()).isEqualTo("Order shipped");
        assertThat(payload.get("body").textValue()).isEqualTo("Your order is on its way");
        assertThat(payload.get("metadata").get("orderId").textValue()).isEqualTo("A-1");
        assertThat(payload.get("createdAt").isNull()).isFalse();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, provider, provider_message_id, attempt_count, sender FROM notifications WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("DELIVERED");
        assertThat(row.get("provider")).isEqualTo("webhook");
        assertThat(row.get("provider_message_id")).as("a webhook receiver returns no message id").isNull();
        assertThat(row.get("attempt_count")).isEqualTo(1);
        assertThat(row.get("sender")).as("the sender snapshot is an email concept").isNull();

        List<Map<String, Object>> attempts = attempts(id);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).get("outcome")).isEqualTo("SUCCESS");
        assertThat(attempts.get(0).get("provider")).isEqualTo("webhook");
        // A successful attempt stores no HTTP status: PersistableOutcome.Delivered carries provider and message id
        // only (shape, channel-agnostic). The status is recorded on failures, where it drives the classification.
        assertThat(attempts.get(0).get("http_status")).isNull();
    }

    @Test
    @DisplayName("a 503 is retryable: the notification goes to RETRYING with a retry time and a trigger, body unchanged")
    void serverErrorIsRetried() throws Exception {
        MODE.set(Mode.SERVER_ERROR);
        UUID id = seedQueued(0, 5);

        publish(id, 0);
        awaitStatus(id, "RETRYING");
        awaitAllCommitted();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT attempt_count, next_retry_at, last_error_code FROM notifications WHERE id = ?", id);
        assertThat(row.get("attempt_count")).isEqualTo(1);
        assertThat(row.get("next_retry_at")).isNotNull();
        assertThat(row.get("last_error_code")).isEqualTo("WEBHOOK_SERVER_ERROR");

        List<Map<String, Object>> attempts = attempts(id);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).get("outcome")).isEqualTo("RETRYABLE_FAILURE");
        assertThat(attempts.get(0).get("http_status")).isEqualTo(503);
        assertThat(scheduler.hasRetryTrigger(id)).isTrue();
        assertThat(RECEIVED).hasSize(1);
    }

    @Test
    @DisplayName("a 404 is permanent: FAILED with a dead-letter outbox event and no retry trigger")
    void clientErrorIsPermanent() throws Exception {
        MODE.set(Mode.NOT_FOUND);
        UUID id = seedQueued(0, 5);

        publish(id, 0);
        awaitStatus(id, "FAILED");
        awaitAllCommitted();

        assertThat(jdbc.queryForObject(
                "SELECT last_error_code FROM notifications WHERE id = ?", String.class, id))
                .isEqualTo("WEBHOOK_CLIENT_ERROR");
        assertThat(jdbc.queryForList(
                "SELECT id FROM outbox_events WHERE aggregate_id = ? AND topic = 'notify.dlq.v1'", id)).hasSize(1);
        assertThat(scheduler.hasRetryTrigger(id)).isFalse();
        assertThat(attempts(id).get(0).get("outcome")).isEqualTo("PERMANENT_FAILURE");
    }

    @Test
    @DisplayName("a receiver that never answers is a retryable timeout, bounded by the configured budget")
    void timeoutIsRetried() throws Exception {
        MODE.set(Mode.HANG);
        UUID id = seedQueued(0, 5);

        publish(id, 0);
        awaitStatus(id, "RETRYING");

        assertThat(jdbc.queryForObject(
                "SELECT last_error_code FROM notifications WHERE id = ?", String.class, id))
                .isEqualTo("WEBHOOK_TIMEOUT");
        assertThat(attempts(id).get(0).get("http_status")).isNull();
    }

    @Test
    @DisplayName("a disabled endpoint fails permanently without any HTTP call")
    void disabledEndpointIsPermanentAndSendsNothing() throws Exception {
        endpointService.upsert(tenantId, demoUrl(), false);
        UUID id = seedQueued(0, 5);

        publish(id, 0);
        awaitStatus(id, "FAILED");
        awaitAllCommitted();

        assertThat(RECEIVED).as("nothing is sent to a disabled destination").isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT last_error_code FROM notifications WHERE id = ?", String.class, id))
                .isEqualTo("WEBHOOK_ENDPOINT_DISABLED");
    }

    @Test
    @DisplayName("a tenant with no endpoint at all fails permanently, not endlessly")
    void missingEndpointIsPermanent() throws Exception {
        UUID otherTenant = tenantRepository.save(
                new TenantEntity("NoEndpoint", "no-endpoint-" + UUID.randomUUID())).getId();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, 'WEBHOOK', 'QUEUED', 'https://receiver.example.com/hook', 'S', 'B', 0, 1, 0, 5, now())
                """, id, otherTenant);

        WorkerKafkaSupport.publish(bootstrap(), TOPIC, id.toString(),
                WorkerKafkaSupport.dispatchEvent(otherTenant, id, 0, "NOTIFICATION_QUEUED", "WEBHOOK"));
        awaitStatus(id, "FAILED");

        assertThat(jdbc.queryForObject(
                "SELECT last_error_code FROM notifications WHERE id = ?", String.class, id))
                .isEqualTo("WEBHOOK_ENDPOINT_MISSING");
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("F6 an oversize multibyte error page is stored bounded, valid UTF-8, and visibly truncated")
    void oversizeFailureSnippetIsStoredWithItsMarker() throws Exception {
        MODE.set(Mode.OVERSIZE_MULTIBYTE);
        UUID id = seedQueued(0, 5);

        publish(id, 0);
        awaitStatus(id, "RETRYING");
        awaitAllCommitted();

        // The value as PostgreSQL actually holds it, not as the mapper produced it.
        String stored = jdbc.queryForObject(
                "SELECT response_snippet FROM delivery_attempts WHERE notification_id = ?", String.class, id);

        assertThat(stored).isNotNull().endsWith("...[truncated]");
        assertThat(stored.getBytes(StandardCharsets.UTF_8).length)
                .as("the approved 4096-byte budget reaches the database, marker included, and beats the old 1 KB cap")
                .isGreaterThan(1024)
                .isLessThanOrEqualTo(4096);
        assertThat(stored).doesNotContain("�");
        String head = stored.substring(0, stored.length() - "...[truncated]".length());
        assertThat(head).isNotEmpty();
        assertThat(head.chars().allMatch(c -> c == '☃')).as("no code point was split").isTrue();
        // Characters, as PostgreSQL counts them for VARCHAR(4096): a 4096-byte UTF-8 value is never wider.
        assertThat(jdbc.queryForObject(
                "SELECT length(response_snippet) FROM delivery_attempts WHERE notification_id = ?", Integer.class, id))
                .isLessThanOrEqualTo(4096);
        // 3 bytes per snowman, so the stored head really is more than a kilobyte of multibyte text.
        assertThat(head.length() * 3).isGreaterThan(1024);
    }

    @Test
    @DisplayName("A1 a Retry-After the receiver sent is recorded in the attempt and raises the due time as a floor")
    void retryAfterIsPersistedAndRaisesTheDueTime() throws Exception {
        MODE.set(Mode.RETRY_AFTER);
        UUID id = seedQueued(0, 5);

        publish(id, 0);
        awaitStatus(id, "RETRYING");
        awaitAllCommitted();

        Map<String, Object> attempt = jdbc.queryForMap(
                "SELECT error_code, error_message, http_status FROM delivery_attempts WHERE notification_id = ?", id);
        assertThat(attempt.get("error_code")).isEqualTo("WEBHOOK_SERVER_ERROR");
        assertThat((String) attempt.get("error_message")).contains("Retry-After: 120");
        assertThat(attempt.get("http_status")).isEqualTo(503);

        // / , decision replaces 's "recorded but never acted on". The header is still
        // recorded verbatim (asserted above), and the delta-seconds form is now ALSO a floor on the due time. The
        // old assertion here - that the due time stays inside the 2 s backoff - asserted exactly the behaviour that
        // decision changed, so it is replaced rather than relaxed: the assertions below are strictly stronger,
        // pinning the floor, the attempt consumption and the clamp.
        Instant nextRetryAt = jdbc.queryForObject(
                "SELECT next_retry_at FROM notifications WHERE id = ?", java.sql.Timestamp.class, id).toInstant();
        Instant finishedAt = jdbc.queryForObject(
                "SELECT finished_at FROM delivery_attempts WHERE notification_id = ?", java.sql.Timestamp.class, id)
                .toInstant();
        assertThat(Duration.between(finishedAt, nextRetryAt))
                .as("the receiver asked for 120 s and the configured backoff is 2 s, so the floor decides")
                .isGreaterThanOrEqualTo(Duration.ofSeconds(120))
                .as("and it is still clamped by notify.resilience.max-retry-after, so a receiver cannot park work")
                .isLessThanOrEqualTo(Duration.ofMinutes(5));

        // The part of that is easy to lose: this was a real request, so the attempt is consumed and recorded.
        // Only a local refusal taken before any provider contact is attempt-free.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempt_count FROM notifications WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("RETRYING");
        assertThat(row.get("attempt_count"))
                .as("a 429 or 503 from a receiver spends an attempt; it is never refunded")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", Long.class, id))
                .as("and it keeps its attempt-history row")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("F8 a pre-send refusal is attributed to notify-system in the persisted attempt, not to the provider")
    void preSendRefusalIsAttributedToTheSystem() throws Exception {
        endpointService.upsert(tenantId, demoUrl(), false);
        UUID id = seedQueued(0, 5);

        publish(id, 0);
        awaitStatus(id, "FAILED");
        awaitAllCommitted();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT provider, error_code FROM delivery_attempts WHERE notification_id = ?", id);
        assertThat(row.get("error_code")).isEqualTo("WEBHOOK_ENDPOINT_DISABLED");
        assertThat(row.get("provider"))
                .as("CONTRACTS: a missing, disabled or blocked endpoint records notify-system")
                .isEqualTo("notify-system");
        // notifications.provider is written only on the delivered path (behaviour, unchanged); the contract's
        // statement is about delivery_attempts.provider, asserted above.
        assertThat(jdbc.queryForObject(
                "SELECT provider FROM notifications WHERE id = ?", String.class, id)).isNull();
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("F1 a transient DNS failure is retried on the ordinary budget instead of failing on attempt one")
    void dnsFailureConsumesTheRetryBudget() throws Exception {
        // A syntactically valid public host that resolves nowhere: the policy accepts the URL, resolution fails.
        String unresolvable = "https://no-such-host-" + UUID.randomUUID() + ".example.com/hook";
        jdbc.update("UPDATE webhook_endpoints SET url = ?, is_demo = false WHERE tenant_id = ?",
                unresolvable, tenantId);
        UUID id = seedQueued(0, 5);

        publish(id, 0);
        awaitStatus(id, "RETRYING");
        awaitAllCommitted();

        Map<String, Object> notification = jdbc.queryForMap(
                "SELECT status, attempt_count, last_error_code, next_retry_at FROM notifications WHERE id = ?", id);
        assertThat(notification.get("status")).as("not FAILED on the first attempt").isEqualTo("RETRYING");
        assertThat(notification.get("attempt_count")).isEqualTo(1);
        assertThat(notification.get("last_error_code")).isEqualTo("WEBHOOK_UNREACHABLE");
        assertThat(notification.get("next_retry_at")).isNotNull();

        Map<String, Object> attempt = jdbc.queryForMap(
                "SELECT outcome, provider, http_status FROM delivery_attempts WHERE notification_id = ?", id);
        assertThat(attempt.get("outcome")).isEqualTo("RETRYABLE_FAILURE");
        assertThat(attempt.get("provider")).isEqualTo("webhook");
        assertThat(attempt.get("http_status")).isNull();
        assertThat(jdbc.queryForList(
                "SELECT id FROM outbox_events WHERE aggregate_id = ? AND topic = 'notify.dlq.v1'", id))
                .as("a DNS outage must not dead-letter the notification").isEmpty();
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("F2 the signed body is byte-identical across a real retry release and reclaim, though headers differ")
    void bodyIsIdenticalAcrossARealRetry() throws Exception {
        MODE.set(Mode.SERVER_ERROR);
        UUID id = seedQueued(0, 5);

        publish(id, 0);
        awaitStatus(id, "RETRYING");
        awaitAllCommitted();
        assertThat(RECEIVED).hasSize(1);

        // A real release: the row goes back to QUEUED at a NEW queued_at and a new generation, exactly as Quartz does.
        Instant queuedAtBefore = jdbc.queryForObject(
                "SELECT queued_at FROM notifications WHERE id = ?", java.sql.Timestamp.class, id).toInstant();
        MODE.set(Mode.OK);
        // The release is due-time fenced; bring the due time forward instead of sleeping out the backoff.
        // The timestamp comes from this JVM and is back-dated, not from the database's now(). The fence in
        // releaseRetryConditional compares next_retry_at against the application's injected Clock, which runs in
        // this JVM, while now() runs on the PostgreSQL container's clock; comparing one clock's output against
        // another's is what left the row not yet due in one gate run, so releaseRetry correctly returned false.
        // Writing the fenced column from the same clock that reads it removes the cross-clock comparison, and the
        // minute of margin also absorbs any write the worker commits later than the offset commit awaited above.
        jdbc.update("UPDATE notifications SET next_retry_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), id);
        assertThat(transitioner.releaseRetry(tenantId, id, 0, "test release")).isTrue();
        Instant queuedAtAfter = jdbc.queryForObject(
                "SELECT queued_at FROM notifications WHERE id = ?", java.sql.Timestamp.class, id).toInstant();
        assertThat(queuedAtAfter).as("the release really did rewrite queued_at").isAfter(queuedAtBefore);

        publish(id, 1);
        awaitStatus(id, "DELIVERED");
        awaitAllCommitted();

        assertThat(RECEIVED).hasSize(2);
        Received first = RECEIVED.get(0);
        Received second = RECEIVED.get(1);
        assertThat(second.body()).as("byte-identical body for every attempt of one delivery cycle")
                .isEqualTo(first.body());
        assertThat(second.deliveryId()).as("the delivery id is stable within the cycle").isEqualTo(first.deliveryId());
        assertThat(second.attempt()).as("the attempt header does change").isNotEqualTo(first.attempt());
        assertThat(second.signatureValid()).isTrue();
        assertThat(first.signatureValid()).isTrue();
    }

    @Test
    @DisplayName("a duplicate dispatch record for a delivered notification is discarded, and the receiver sees one POST")
    void duplicateRecordIsDiscarded() throws Exception {
        UUID id = seedQueued(0, 5);

        publish(id, 0);
        awaitStatus(id, "DELIVERED");
        publish(id, 0);
        awaitAllCommitted();

        assertThat(RECEIVED).hasSize(1);
        assertThat(attempts(id)).hasSize(1);
    }
}
