package com.notify.dlq.service;

import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.common.model.DlqStatus;
import com.notify.dlq.dto.DeadLetterEventEnvelope;
import com.notify.dlq.dto.DlqReplayResponse;
import com.notify.dlq.repository.DeadLetterEntryRepository;
import com.notify.dlq.service.DeadLetterMaterializationService.RecordCoordinates;
import com.notify.kafka.config.KafkaTopicsConfig;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import com.notify.webhook.service.WebhookEndpointService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A replayed notification is delivered by the real channel worker, on all three channels (/ , ;
 * ).
 *
 * <p><b>Nothing is driven by hand after the replay.</b> The admin replay writes the {@code NOTIFICATION_REPLAYED}
 * event inside its own transaction; the real outbox relay publishes it to the contracted dispatch topic; the real
 * channel worker consumes it and delivers. That chain is the claim: a replay is not a status edit, it is work that
 * re-enters delivery through the existing seams - which is why no new event type and no new topic were introduced.
 *
 * <p>What each case asserts: the notification reaches DELIVERED, the <b>new</b> delivery cycle carries the new
 * attempt, the attempt from the failed cycle is <b>still there</b>, and the entry is REPLAYED with
 * {@code replay_count} 1. Old attempts surviving is the point of the per-cycle attempt key ().
 *
 * <p>Disposable only: this suite's own PostgreSQL and Kafka, the production-wired <em>fake</em> email provider, and
 * a loopback HTTP receiver for the webhook. Nothing outside this JVM is contacted and no live provider is used.
 */
@TestPropertySource(properties = {
        "notify.worker.email.enabled=true",
        "notify.worker.webhook.enabled=true",
        "notify.worker.inapp.enabled=true",
        "notify.worker.concurrency.email=1",
        "notify.worker.concurrency.webhook=1",
        "notify.worker.concurrency.inapp=1",
        "notify.outbox.relay-enabled=true",
        "notify.outbox.relay-interval-ms=300",
        "notify.reconciler.enabled=false",
        "notify.recovery.enabled=false",
        "notify.email.provider=fake",
        "notify.mail.from=noreply@replay.notify.local",
        "notify.webhook.demo-allowlist=127.0.0.1,localhost",
        "notify.webhook.connect-timeout=500ms",
        "notify.webhook.total-timeout=1s",
        "notify.webhook.dns-timeout=500ms",
        "notify.retry.base-delay=1s",
        "notify.retry.max-delay=5s"
})
@SpringBootTest
@ActiveProfiles("test")
@SuppressWarnings("deprecation")
// / -3: this class enables background work (relay, reconciler, reaper or worker). Spring caches
// test contexts for the whole JVM run, so without this the schedulers keep polling after the class finishes -
// and the outbox relay's fetch is global, with no tenant predicate. Dirtying the context shuts those pools
// down at class exit instead of leaking background polling into later suites.
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ReplayToDeliveryPerChannelIntegrationTest {

    /**
     * This suite owns <b>both</b> containers, and does not extend {@code AbstractIsolatedDatabaseTest}.
     *
     * <p>Unique consumer groups would not have been enough. Production resolves a dispatch topic from a constant
     * ({@code KafkaTopicsConfig.resolveDispatchTopic}), so this suite must publish to and consume from the
     * contracted topics - there is no production routing seam to isolate, and adding one for a test is not
     * acceptable. On a broker shared with the other suites of that hierarchy, a unique group would then start at
     * {@code earliest} and read <b>every</b> record another fixture had ever published to those topics.
     *
     * <p>The database has the mirror-image problem: this is a relay-enabled suite, and the outbox relay scans
     * {@code outbox_events} globally, so on a shared database it would publish another fixture's pending events.
     *
     * <p>Hence one disposable PostgreSQL and one disposable Kafka of its own, plus fixture-owned group ids as
     * defence in depth. The cost is one extra container for one suite; the benefit is that neither direction of
     * cross-fixture processing is possible.
     */
    private static final PostgreSQLContainer<?> ownPostgres;
    private static final KafkaContainer ownKafka;

    static {
        ownPostgres = new PostgreSQLContainer<>("postgres:16-alpine");
        ownPostgres.start();
        ownKafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));
        ownKafka.start();
    }

    @DynamicPropertySource
    static void ownInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ownPostgres::getJdbcUrl);
        registry.add("spring.datasource.username", ownPostgres::getUsername);
        registry.add("spring.datasource.password", ownPostgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", ownKafka::getBootstrapServers);
        // Fixture-owned groups, so even on a broker this suite did not own, it could not share a group position.
        registry.add("notify.worker.email.group-id", () -> "n17-replay-email-" + RUN);
        registry.add("notify.worker.webhook.group-id", () -> "n17-replay-webhook-" + RUN);
        registry.add("notify.worker.inapp.group-id", () -> "n17-replay-inapp-" + RUN);
    }

    private static final String RUN = UUID.randomUUID().toString();

    private static final Duration WAIT = Duration.ofSeconds(120);
    private static final AtomicInteger OFFSETS = new AtomicInteger();
    private static final String HOOK_PATH = "/demo-hook";

    private static final HttpServer RECEIVER;
    private static final int PORT;

    static {
        try {
            // The contracted dispatch topics, because the relay publishes to those and nowhere else.
            java.util.Properties adminProperties = new java.util.Properties();
            adminProperties.put("bootstrap.servers", ownKafka.getBootstrapServers());
            try (org.apache.kafka.clients.admin.Admin admin =
                         org.apache.kafka.clients.admin.Admin.create(adminProperties)) {
                admin.createTopics(java.util.stream.Stream.of(
                                KafkaTopicsConfig.TOPIC_DISPATCH_EMAIL, KafkaTopicsConfig.TOPIC_DISPATCH_EMAIL_DLT,
                                KafkaTopicsConfig.TOPIC_DISPATCH_WEBHOOK,
                                KafkaTopicsConfig.TOPIC_DISPATCH_WEBHOOK_DLT,
                                KafkaTopicsConfig.TOPIC_DISPATCH_INAPP, KafkaTopicsConfig.TOPIC_DISPATCH_INAPP_DLT)
                        .map(name -> new org.apache.kafka.clients.admin.NewTopic(name, 3, (short) 1))
                        .toList()).all().get();
            } catch (java.util.concurrent.ExecutionException e) {
                // On this suite's own broker the topics cannot pre-exist; the tolerance stays because an
                // existing topic is the desired state either way and must never fail class initialization.
                if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                    throw e;
                }
            }
            RECEIVER = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            PORT = RECEIVER.getAddress().getPort();
            RECEIVER.createContext(HOOK_PATH, exchange -> {
                try (InputStream in = exchange.getRequestBody()) {
                    in.readAllBytes();
                }
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

    @org.springframework.test.context.DynamicPropertySource
    static void demoDestination(org.springframework.test.context.DynamicPropertyRegistry registry) {
        // The configured demo URL is the only destination for which plain http to a loopback address is allowed
        // (), and it is what makes the endpoint row is_demo.
        registry.add("notify.demo.webhook-url", ReplayToDeliveryPerChannelIntegrationTest::staticDemoUrl);
    }

    private static String staticDemoUrl() {
        return "http://127.0.0.1:" + PORT + HOOK_PATH;
    }

    @Autowired private DeadLetterMaterializationService materialization;
    @Autowired private AdminDlqService adminDlqService;
    @Autowired private DeadLetterEntryRepository entries;
    @Autowired private TenantRepository tenants;
    @Autowired private WebhookEndpointService endpoints;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;
    @Autowired private org.springframework.core.env.Environment environment;

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

    private String demoUrl() {
        return staticDemoUrl();
    }

    /** A notification that failed its first cycle, with the attempt row that cycle produced. */
    private UUID seedFailedWithOneAttempt(Channel channel, String recipient, String provider, String errorCode) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body, sender,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, last_error_code, queued_at)
                VALUES (?, ?, ?, 'FAILED', ?, 'Replay subject', 'Replay body', ?,
                    0, 1, 5, 5, ?, now())
                """, id, tenantId, channel.name(), recipient,
                channel == Channel.EMAIL ? "noreply@replay.notify.local" : null, errorCode);
        jdbc.update("""
                INSERT INTO delivery_attempts (id, tenant_id, notification_id, delivery_cycle, attempt_number,
                    provider, outcome, error_code, started_at, finished_at, latency_ms)
                VALUES (?, ?, ?, 1, 5, ?, 'RETRYABLE_FAILURE', ?, now(), now(), 9)
                """, UUID.randomUUID(), tenantId, id, provider, errorCode);
        DeadLetterEventEnvelope event = new DeadLetterEventEnvelope(
                1, UUID.randomUUID(), DeadLetterEventEnvelope.EVENT_TYPE, id, tenantId, channel, 0, 1,
                DlqReason.MAX_RETRIES_EXCEEDED, errorCode, 5, clock.instant().toString());
        materialization.apply(event, new RecordCoordinates(
                fixtureTopic, 0, OFFSETS.incrementAndGet(), "{}".getBytes(StandardCharsets.UTF_8)));
        return id;
    }

    private DlqReplayResponse replay(UUID notificationId) {
        UUID entryId = entries.findByNotificationId(notificationId).orElseThrow().getId();
        return adminDlqService.replay(entryId, "acceptance: replay to delivery",
                new AdminDlqService.AdminActor(UUID.randomUUID(), "admin@notify.local", "127.0.0.1"));
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id);
    }

    private List<Map<String, Object>> attempts(UUID id) {
        return jdbc.queryForList("""
                SELECT delivery_cycle, attempt_number, outcome, provider FROM delivery_attempts
                WHERE notification_id = ? ORDER BY delivery_cycle, attempt_number
                """, id);
    }

    /**
     * The shared assertions: delivered on the new cycle, the failed cycle's attempt intact, the entry REPLAYED.
     */
    private void assertDeliveredOnANewCycle(UUID id, DlqReplayResponse response, String expectedProvider) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(status(id)).isEqualTo("DELIVERED"));

        assertThat(response.newDeliveryCycle()).isEqualTo(2);
        assertThat(response.replayCount()).isEqualTo(1);
        assertThat(response.possibleDuplicateDelivery())
                .as("a replay issues a new send identity, so a second external effect is possible")
                .isTrue();

        assertThat(jdbc.queryForObject("SELECT delivery_cycle FROM notifications WHERE id = ?", Integer.class, id))
                .isEqualTo(2);
        List<Map<String, Object>> rows = attempts(id);
        assertThat(rows)
                .as("the failed cycle's attempt is retained and the new cycle adds its own")
                .hasSize(2);
        assertThat(rows.get(0).get("delivery_cycle")).isEqualTo(1);
        assertThat(rows.get(0).get("outcome")).isEqualTo("RETRYABLE_FAILURE");
        assertThat(rows.get(1).get("delivery_cycle")).isEqualTo(2);
        assertThat(rows.get(1).get("outcome")).isEqualTo("SUCCESS");
        assertThat(rows.get(1).get("provider")).isEqualTo(expectedProvider);

        assertThat(entries.findByNotificationId(id).orElseThrow().getStatus()).isEqualTo(DlqStatus.REPLAYED);
        assertThat(jdbc.queryForObject(
                "SELECT replay_count FROM dead_letter_entries WHERE notification_id = ?", Integer.class, id))
                .isEqualTo(1);
    }


    @Test
    @DisplayName("isolation guard: this context resolved this suite's own database and broker, nothing shared")
    void theContextUsesThisSuitesOwnInfrastructure() {
        assertThat(environment.getProperty("spring.datasource.url"))
                .as("a relay-enabled suite on a shared database would publish another fixture's outbox rows")
                .isEqualTo(ownPostgres.getJdbcUrl());
        assertThat(environment.getProperty("spring.kafka.bootstrap-servers"))
                .as("and on a shared broker it would consume another fixture's dispatch records")
                .isEqualTo(ownKafka.getBootstrapServers());
        assertThat(environment.getProperty("notify.worker.email.group-id")).startsWith("n17-replay-email-");
    }
    @Test
    @DisplayName("EMAIL: a replayed notification is delivered by the email worker on the new cycle")
    void emailReplayReachesDelivered() {
        UUID id = seedFailedWithOneAttempt(
                Channel.EMAIL, "rcpt@replay.notify.local", "fake", "PROVIDER_UNAVAILABLE");

        assertDeliveredOnANewCycle(id, replay(id), "fake");
    }

    @Test
    @DisplayName("WEBHOOK: a replayed notification is delivered by the webhook worker on the new cycle")
    void webhookReplayReachesDelivered() {
        endpoints.upsert(tenantId, demoUrl(), null);
        UUID id = seedFailedWithOneAttempt(
                Channel.WEBHOOK, demoUrl(), "webhook", "WEBHOOK_SERVER_ERROR");

        assertDeliveredOnANewCycle(id, replay(id), "webhook");
    }

    @Test
    @DisplayName("IN_APP: a replayed notification is delivered by the in-app worker and writes its row")
    void inAppReplayReachesDelivered() {
        UUID id = seedFailedWithOneAttempt(Channel.IN_APP, "user-replay-1", "inapp", "INAPP_WRITE_FAILED");

        assertDeliveredOnANewCycle(id, replay(id), "inapp");

        assertThat(jdbc.queryForList("SELECT seq FROM inapp_messages WHERE notification_id = ?", id))
                .as("the durable local write is what DELIVERED means for IN_APP")
                .hasSize(1);
    }
}
