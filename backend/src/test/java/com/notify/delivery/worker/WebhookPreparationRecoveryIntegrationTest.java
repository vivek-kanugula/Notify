package com.notify.delivery.worker;

import com.notify.delivery.provider.ChannelPreparationException;
import com.notify.delivery.worker.config.WorkerRecoverySupport;
import com.notify.delivery.worker.control.WorkerContainerController;
import com.notify.quartz.service.NotificationSchedulerService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import com.notify.webhook.service.WebhookEndpointService;
import com.sun.net.httpserver.HttpServer;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * The repaired database-recovery path for webhook delivery, end to end (/ ).
 *
 * <p><b>The defect.</b> The tenant gate reads the database and succeeds, the claim commits, and only then the endpoint
 * read fails. Before the repair that failure was converted into {@code PROVIDER_UNAVAILABLE}: a retryable provider
 * failure with an attempt row, a consumed attempt and — at the budget's end — a {@code MAX_RETRIES_EXCEEDED} result
 * and a dead-letter outbox event, all for a webhook that was never sent. After the repair the accepted policy
 * owns it: pause for connectivity, stop for a non-transient fault, offset retained either way, and no delivery result
 * written at all.
 *
 * <p><b>What the repair deliberately does not do.</b> The claim is already committed when preparation fails, so the
 * row stays {@code PROCESSING} and is not reclaimed by a redelivery — the documented /crash window whose
 * automatic recovery is /. Nothing here refunds a claim, rewrites a state or adds orphan recovery. The
 * difference the repair makes is that the row no longer carries a fabricated provider outcome and no longer loses
 * attempts from its delivery budget while the database is unavailable.
 *
 * <p><b>Failure injection.</b> A spy on {@link WebhookEndpointService} throws from the delivery-path method only.
 * That is deterministic and leaves the database fully available, which this case needs: the tenant gate and the claim
 * must both succeed before preparation is reached. Pausing the database container — the technique
 * {@code EmailWorkerFailureIntegrationTest} uses for K5 — cannot express it, because the claim would fail instead.
 *
 * <p><b>Why the assertions are per notification.</b> Nothing here is timed against the pause window. Each test waits
 * for its own row to reach {@code PROCESSING} — which proves the claim committed and preparation ran — and then
 * asserts facts about that row that can never become true later: no attempt row, no dead-letter event, no provider
 * attribution, no retry. For the two recoverable categories it then waits for the <em>end state</em> the pre-existing
 * /crash window produces, a quarantine of the record with reason {@code UNRESOLVED_PROCESSING_STUCK}, and
 * checks the same invariants again. A quarantine for a stuck {@code PROCESSING} row is not a provider failure, writes
 * no attempt and sends no webhook, which is exactly the distinction under test.
 *
 * <p>Own disposable PostgreSQL and Kafka containers, an isolated topic and group, and a loopback receiver that records
 * every request. Nothing can reach the development database, the development broker or any external host, and the
 * webhook encryption key is the fixed non-production test key from {@code application-test.yml}.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "notify.worker.webhook.enabled=true",
        "notify.worker.concurrency.webhook=1",
        "notify.worker.webhook.retry-interval=100ms",
        "notify.worker.webhook.pause-initial=300ms",
        "notify.worker.webhook.pause-max=1s",
        "notify.worker.webhook.escalate-after=3",
        "notify.worker.webhook.health-poll-interval=200ms",
        "notify.worker.webhook.health-successes=2",
        "notify.retry.base-delay=30s",
        "notify.retry.max-delay=60s",
        "notify.webhook.demo-allowlist=127.0.0.1,localhost",
        "notify.webhook.connect-timeout=500ms",
        "notify.webhook.total-timeout=1s",
        "notify.webhook.dns-timeout=500ms"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("deprecation")
class WebhookPreparationRecoveryIntegrationTest {

    static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));
    static final String TOPIC = WorkerKafkaSupport.uniqueTopic("notify.test.webhook.prep");
    static final String DLT = TOPIC + ".DLT";
    static final String GROUP = "grp-" + TOPIC;
    private static final Duration WAIT = Duration.ofSeconds(90);
    private static final String HOOK_PATH = "/demo-hook";

    private static final HttpServer RECEIVER;
    private static final int PORT;
    /** Every request the receiver saw. It must stay empty for a preparation failure. */
    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

    static {
        db.start();
        kafka.start();
        try {
            WorkerKafkaSupport.createTopics(kafka.getBootstrapServers(), 3, TOPIC, DLT);
            RECEIVER = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            PORT = RECEIVER.getAddress().getPort();
            RECEIVER.createContext(HOOK_PATH, exchange -> {
                RECEIVED.add(exchange.getRequestURI().toString());
                byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response);
                }
                exchange.close();
            });
            RECEIVER.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String demoUrl() {
        return "http://127.0.0.1:" + PORT + HOOK_PATH;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("notify.worker.webhook.topic", () -> TOPIC);
        registry.add("notify.worker.webhook.group-id", () -> GROUP);
        registry.add("notify.demo.webhook-url", WebhookPreparationRecoveryIntegrationTest::demoUrl);
    }

    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkerContainerController controller;
    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired private NotificationSchedulerService scheduler;
    @MockitoSpyBean private WebhookEndpointService endpointService;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        RECEIVED.clear();
        tenantId = tenantRepository.save(new TenantEntity("PrepOrg", "prep-org-" + UUID.randomUUID())).getId();
        endpointService.upsert(tenantId, demoUrl(), null);
    }

    @AfterEach
    void stopInjecting() {
        doCallRealMethod().when(endpointService).resolveDestinationForDelivery(any());
    }

    private static JDBCConnectionException connectionLost() {
        return new JDBCConnectionException("could not obtain connection",
                new SQLException("connection refused", "08006"));
    }

    private void failPreparationWith(RuntimeException cause) {
        doThrow(new ChannelPreparationException("Webhook endpoint lookup failed before any provider request", cause))
                .when(endpointService).resolveDestinationForDelivery(any());
    }

    private UUID seedQueued(int maxAttempts) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, 'WEBHOOK', 'QUEUED', ?, 'Order shipped', 'Your order is on its way', 0, 1, 0, ?, now())
                """, id, tenantId, demoUrl(), maxAttempts);
        return id;
    }

    private void publish(UUID id) throws Exception {
        WorkerKafkaSupport.publish(bootstrap(), TOPIC, id.toString(),
                WorkerKafkaSupport.dispatchEvent(tenantId, id, 0, "NOTIFICATION_QUEUED", "WEBHOOK"));
    }

    private String bootstrap() {
        return kafka.getBootstrapServers();
    }

    private long uncommitted() throws Exception {
        return WorkerKafkaSupport.uncommitted(bootstrap(), GROUP, TOPIC);
    }

    private Map<String, Object> notificationRow(UUID id) {
        return jdbc.queryForMap("""
                SELECT status, attempt_count, claim_seq, next_retry_at, last_error_code, provider
                FROM notifications WHERE id = ?
                """, id);
    }

    private List<Map<String, Object>> attempts(UUID id) {
        return jdbc.queryForList("SELECT * FROM delivery_attempts WHERE notification_id = ?", id);
    }

    /** Dead-letter outbox events, which only a persisted {@code Fail} outcome can write. */
    private long deadLetterEvents(UUID id) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events
                WHERE aggregate_id = ? AND event_type = 'NOTIFICATION_DEAD_LETTERED'
                """, Long.class, id);
    }

    /** Waits for this row to be claimed, which is the proof that the record was processed and preparation ran. */
    private void awaitClaimed(UUID id) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(notificationRow(id).get("status")).isEqualTo("PROCESSING"));
    }

    /** DLT records for one notification, by key. */
    private List<org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]>> dltFor(UUID id) {
        return WorkerKafkaSupport.readAll(bootstrap(), DLT).stream()
                .filter(record -> id.toString().equals(record.key()))
                .toList();
    }

    private String dltReason(org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]> record) {
        var header = record.headers().lastHeader(WorkerRecoverySupport.DLT_REASON_HEADER);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * Everything that must be absent after a preparation failure, whatever its category: no request left the process,
     * and no provider outcome was persisted anywhere.
     */
    private void assertNothingWasSentAndNoProviderOutcomeWritten(UUID id) {
        assertThat(RECEIVED).as("no webhook request was made").isEmpty();
        assertThat(attempts(id)).as("no delivery_attempts row for a request that was never sent").isEmpty();
        assertThat(deadLetterEvents(id)).as("no dead-letter outbox event").isZero();

        Map<String, Object> row = notificationRow(id);
        assertThat(row.get("status")).as("no result was persisted, so the row is still the claim's PROCESSING")
                .isEqualTo("PROCESSING");
        assertThat(row.get("next_retry_at")).as("no retry scheduled by a fabricated outcome").isNull();
        assertThat(row.get("last_error_code")).as("no provider error code written").isNull();
        assertThat(row.get("provider")).as("no provider attributed").isNull();
        assertThat(scheduler.hasRetryTrigger(id)).as("no Quartz retry trigger").isFalse();

        // The claim is a different thing from a persisted provider attempt, and it legitimately happened: the row was
        // claimed once, so claim_seq is 1 and attempt_count is 1. Neither is refunded or rewritten by this repair;
        // automatic recovery of such a row is /.
        assertThat(row.get("claim_seq")).as("the committed claim, not refunded").isEqualTo(1);
        assertThat(row.get("attempt_count")).as("the claim's counter, not a persisted provider attempt").isEqualTo(1);
    }

    // ─── CONNECTIVITY ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("a connectivity failure during webhook preparation pauses the partition: no request, no attempt row, no provider outcome, offset retained, container still running")
    void connectivityDuringPreparationPausesInsteadOfFabricatingAnOutcome() throws Exception {
        // Warm-up: successful preparation still delivers, so the assertions below describe the injected failure and
        // not a broken fixture.
        UUID warm = seedQueued(5);
        publish(warm);
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(notificationRow(warm).get("status")).isEqualTo("DELIVERED"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(uncommitted()).isZero());
        assertThat(RECEIVED).as("the warm-up really was sent").hasSize(1);
        assertThat(attempts(warm)).hasSize(1);
        RECEIVED.clear();

        failPreparationWith(connectionLost());
        UUID id = seedQueued(5);
        publish(id);
        awaitClaimed(id);

        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> assertThat(
                controller.snapshot().pausedPartitions()).as("partition paused for connectivity").isNotEmpty());

        assertNothingWasSentAndNoProviderOutcomeWritten(id);
        assertThat(registry.getListenerContainer(WorkerContainerController.WEBHOOK_LISTENER_ID).isRunning())
                .as("connectivity pauses the partition; it does not stop the container").isTrue();
        assertThat(controller.snapshot().stopped()).as("connectivity never stops the container").isFalse();
        assertThat(dltFor(id)).as("a database failure is not dead-lettered as a delivery failure").isEmpty();

        // End state: the row stays PROCESSING because its claim committed, so when the partition resumes the
        // redelivery cannot reclaim it and the record is quarantined for being stuck — the documented /crash
        // window, unchanged by this repair. What matters is that even then nothing was sent and no provider outcome
        // exists: before the repair this same record would have carried a PROVIDER_UNAVAILABLE attempt instead.
        stopInjecting();
        await().atMost(WAIT).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(dltFor(id)).as("quarantined once the partition resumed").isNotEmpty());
        assertThat(dltReason(dltFor(id).get(0)))
                .isEqualTo(WorkerRecoverySupport.REASON_UNRESOLVED_PROCESSING_STUCK);
        assertNothingWasSentAndNoProviderOutcomeWritten(id);
    }

    // ─── Budget boundary ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("preparation failing at the delivery-budget boundary creates no exhaustion result and no dead-letter event")
    void noFalseExhaustionAtTheBudgetBoundary() throws Exception {
        failPreparationWith(connectionLost());
        // max_attempts 1, so the claim takes attempt_count to its ceiling: precisely the state in which a retryable
        // provider failure becomes Fail(MAX_RETRIES_EXCEEDED) with a dead-letter event. Preparation must never reach
        // that decision.
        UUID id = seedQueued(1);
        publish(id);
        awaitClaimed(id);

        assertNothingWasSentAndNoProviderOutcomeWritten(id);
        assertThat(notificationRow(id).get("status")).as("not FAILED and not dead-lettered").isEqualTo("PROCESSING");
        assertThat(dltFor(id)).as("no delivery-failure quarantine at this point").isEmpty();

        // Same end state as above, and the same point: the quarantine reason is a stuck PROCESSING row, never
        // MAX_RETRIES_EXCEEDED, and no dead-letter outbox event was written for a provider that was never called.
        stopInjecting();
        await().atMost(WAIT).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(dltFor(id)).isNotEmpty());
        assertThat(dltReason(dltFor(id).get(0)))
                .isEqualTo(WorkerRecoverySupport.REASON_UNRESOLVED_PROCESSING_STUCK);
        assertNothingWasSentAndNoProviderOutcomeWritten(id);
    }

    // ─── NON_TRANSIENT ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("a non-transient preparation failure stops the container with the offset retained, instead of being guessed into a provider outcome")
    void nonTransientPreparationFailureStopsTheContainer() throws Exception {
        failPreparationWith(new DataIntegrityViolationException("relation \"webhook_endpoints\" does not exist"));
        UUID id = seedQueued(5);
        publish(id);
        awaitClaimed(id);

        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(controller.snapshot().stopped()).as("container stop requested").isTrue());

        assertThat(controller.snapshot().stopReason()).contains("NON_TRANSIENT");
        // A stopped container never resumes, so this record's offset stays uncommitted for good.
        assertThat(uncommitted()).as("offset retained by the stop").isGreaterThanOrEqualTo(1);
        assertThat(dltFor(id)).as("a stop never dead-letters").isEmpty();
        assertNothingWasSentAndNoProviderOutcomeWritten(id);
    }
}
