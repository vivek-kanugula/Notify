package com.notify.delivery.worker;

import com.notify.delivery.worker.control.EmailWorkerHealthIndicator;
import com.notify.delivery.worker.control.WorkerContainerController;
import com.notify.quartz.service.NotificationSchedulerService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import com.notify.delivery.model.DeliveryResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;

/**
 * K5 (database outage), K6 (contention / ordering exhaustion), K7 (fatal stop), K9 (Quartz failure after commit).
 * Uses its OWN disposable PostgreSQL and Kafka containers (the outage is a docker pause) with an isolated topic and group. Nothing here can reach the development database or broker.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ScriptedProviderConfig.class)
@TestPropertySource(properties = {
        "notify.worker.email.enabled=true",
        "notify.worker.concurrency.email=1",
        "notify.worker.email.retry-interval=100ms",
        "notify.worker.email.pause-initial=300ms",
        "notify.worker.email.pause-max=1s",
        "notify.worker.email.escalate-after=3",
        "notify.worker.email.health-poll-interval=300ms",
        "notify.worker.email.health-successes=2",
        "notify.reconciler.enabled=true",
        "notify.reconciler.interval-ms=500",
        "notify.retry.base-delay=30s",
        "notify.retry.max-delay=60s"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("deprecation")
// / -3: this class enables background work (relay, reconciler, reaper or worker). Spring caches
// test contexts for the whole JVM run, so without this the schedulers keep polling after the class finishes -
// and the outbox relay's fetch is global, with no tenant predicate. Dirtying the context shuts those pools
// down at class exit instead of leaking background polling into later suites.
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class EmailWorkerFailureIntegrationTest {

    static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));
    static final String TOPIC = WorkerKafkaSupport.uniqueTopic("notify.test.email.fail");
    static final String DLT = TOPIC + ".DLT";
    static final String GROUP = "grp-" + TOPIC;
    private static final Duration WAIT = Duration.ofSeconds(90);

    static {
        db.start();
        kafka.start();
        try {
            WorkerKafkaSupport.createTopics(kafka.getBootstrapServers(), 3, TOPIC, DLT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("notify.worker.email.topic", () -> TOPIC);
        registry.add("notify.worker.email.group-id", () -> GROUP);
    }

    @Autowired private ScriptedEmailProvider provider;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkerContainerController controller;
    @Autowired private EmailWorkerHealthIndicator health;
    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired(required = false) private MeterRegistry meters;
    @MockitoSpyBean private NotificationSchedulerService schedulerSpy;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        provider.reset();
        tenantId = tenantRepository.save(new TenantEntity("FailureOrg", "failure-org-" + UUID.randomUUID())).getId();
    }

    @AfterEach
    void unpauseDatabase() {
        try {
            db.getDockerClient().unpauseContainerCmd(db.getContainerId()).exec();
        } catch (Exception ignored) {
            // not paused
        }
    }

    private String bootstrap() {
        return kafka.getBootstrapServers();
    }

    private UUID seed(String status, int generation) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body, dispatch_generation,
                    delivery_cycle, attempt_count, max_attempts, sender, queued_at)
                VALUES (?, ?, 'EMAIL', ?, 'rcpt@fail.notify.local', 'subject', 'body', ?, 1, 0, 5, 'from@fail.notify.local', now())
                """, id, tenantId, status, generation);
        return id;
    }

    private void publish(UUID id, int generation) throws Exception {
        WorkerKafkaSupport.publish(bootstrap(), TOPIC, id.toString(),
                WorkerKafkaSupport.dispatchEvent(tenantId, id, generation, "NOTIFICATION_QUEUED"));
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id);
    }

    private long uncommitted() throws Exception {
        return WorkerKafkaSupport.uncommitted(bootstrap(), GROUP, TOPIC);
    }

    private double counter(String name, String... tags) {
        if (meters == null) {
            return -1;
        }
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    // ─── K5 ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("K5 database outage: the partition is paused, nothing is committed or dead-lettered, no send happens; after recovery the delivery completes")
    void databaseOutagePausesAndRecovers() throws Exception {
        // warm up: the worker is healthy and processes a record
        UUID warm = seed("QUEUED", 1);
        publish(warm, 1);
        await().atMost(WAIT).untilAsserted(() -> assertThat(status(warm)).isEqualTo("DELIVERED"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(uncommitted()).isZero());
        provider.reset();

        UUID id = seed("QUEUED", 1);
        db.getDockerClient().pauseContainerCmd(db.getContainerId()).exec();
        publish(id, 1);

        await().atMost(WAIT).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(controller.snapshot().pausedPartitions()).as("partition paused for connectivity").isNotEmpty());
        assertThat(provider.calls()).as("no send while the database is unreachable").isZero();
        assertThat(uncommitted()).as("offset retained").isEqualTo(1);
        assertThat(WorkerKafkaSupport.readAll(bootstrap(), DLT)).as("a database outage never dead-letters").isEmpty();
        assertThat(registry.getListenerContainer("notify-email-delivery-worker").isRunning()).as("container keeps running").isTrue();

        db.getDockerClient().unpauseContainerCmd(db.getContainerId()).exec();

        await().atMost(WAIT).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> assertThat(status(id)).isEqualTo("DELIVERED"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(uncommitted()).isZero());
        assertThat(provider.callsFor(id)).isEqualTo(1);
        assertThat(controller.snapshot().pausedPartitions()).isEmpty();
        assertThat(WorkerKafkaSupport.readAll(bootstrap(), DLT)).isEmpty();
        if (meters != null) {
            assertThat(counter("notify.worker.pauses", "reason", "connectivity")).isGreaterThanOrEqualTo(1);
        }
    }

    // ─── K6 ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("K6 ordering exhaustion: the partition is paused repeatedly with escalating delay, offset retained, escalation visible, container still running; fixing the cause completes the delivery")
    void orderingExhaustionPausesEscalatesAndRecovers() throws Exception {
        UUID id = seed("QUEUED", 1);
        publish(id, 2);   // event generation ahead of the database: ORDERING, never claimable

        await().atMost(WAIT).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(controller.snapshot().escalatedPartitions()).as("escalated after 3 consecutive pauses").isNotEmpty());

        assertThat(health.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(registry.getListenerContainer("notify-email-delivery-worker").isRunning()).isTrue();
        assertThat(controller.snapshot().stopped()).isFalse();
        assertThat(uncommitted()).as("offset retained, record not skipped").isEqualTo(1);
        assertThat(WorkerKafkaSupport.readAll(bootstrap(), DLT)).as("exhausted ordering is paused, never dead-lettered").isEmpty();
        assertThat(provider.callsFor(id)).isZero();
        if (meters != null) {
            assertThat(counter("notify.worker.pauses", "reason", "backoff")).isGreaterThanOrEqualTo(3);
            assertThat(counter("notify.worker.escalations")).isGreaterThanOrEqualTo(1);
        }

        jdbc.update("UPDATE notifications SET dispatch_generation = 2 WHERE id = ?", id);   // the missing state arrives

        await().atMost(WAIT).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> assertThat(status(id)).isEqualTo("DELIVERED"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(uncommitted()).isZero());
        await().atMost(WAIT).untilAsserted(() -> assertThat(health.health().getStatus()).isEqualTo(Status.UP));
        assertThat(provider.callsFor(id)).isEqualTo(1);
    }

    // ─── K9 ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("K9 a Quartz failure after the commit: the record is acknowledged, the result stands, and the reconciler repairs the missing trigger")
    void quartzFailureAfterCommit() throws Exception {
        UUID id = seed("QUEUED", 1);
        provider.script(r -> new DeliveryResult.RetryableFailure("PROVIDER_UNAVAILABLE", "down", 503, null));
        // fail only the worker's first scheduling attempt; the reconciler's later call runs the real method
        doThrow(new RuntimeException("quartz down")).doCallRealMethod()
                .when(schedulerSpy).scheduleNotificationRetry(any(), any(), any(), anyInt(), any());

        publish(id, 1);
        await().atMost(WAIT).untilAsserted(() -> assertThat(status(id)).isEqualTo("RETRYING"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(uncommitted()).as("acknowledged despite the Quartz failure").isZero());
        assertThat(provider.callsFor(id)).isEqualTo(1);
        if (meters != null) {
            assertThat(counter("notify.worker.quartz_failures")).isGreaterThanOrEqualTo(1);
        }

        // the existing reconciler (60 s in production, 0.5 s here) restores the trigger
        await().atMost(WAIT).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(schedulerSpy.hasRetryTrigger(id)).as("trigger repaired by the reconciler").isTrue());
        assertThat(status(id)).isEqualTo("RETRYING");
    }

    // ─── K7 (last: it stops the container for good) ────────────────────────────────────────────────────────────────

    @Test
    @Order(99)
    @DisplayName("K7 a fatal state stops the container asynchronously: the listener thread is not blocked, nothing is committed or dead-lettered, health is DOWN")
    void fatalStateStopsContainer() throws Exception {
        UUID id = seed("SCHEDULED", 1);   // a state a dispatch event should never meet: NON_TRANSIENT
        long begin = System.nanoTime();
        publish(id, 1);

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(registry.getListenerContainer("notify-email-delivery-worker").isRunning()).isFalse());
        long elapsedSeconds = Duration.ofNanos(System.nanoTime() - begin).toSeconds();

        assertThat(controller.snapshot().stopped()).isTrue();
        assertThat(controller.snapshot().stopReason()).contains("NON_TRANSIENT");
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(elapsedSeconds).as("a synchronous stop from the listener thread would wait the full shutdown timeout").isLessThan(25);
        assertThat(uncommitted()).as("offset retained").isEqualTo(1);
        assertThat(WorkerKafkaSupport.readAll(bootstrap(), DLT)).isEmpty();
        assertThat(provider.callsFor(id)).isZero();
        assertThat(status(id)).isEqualTo("SCHEDULED");
    }
}
