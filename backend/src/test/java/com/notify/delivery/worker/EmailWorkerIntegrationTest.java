package com.notify.delivery.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractKafkaIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.common.model.TenantStatus;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.worker.config.EmailWorkerProperties;
import com.notify.delivery.worker.control.WorkerContainerController;
import com.notify.notification.dto.CreateNotificationRequest;
import com.notify.notification.service.NotificationService;
import com.notify.quartz.service.NotificationSchedulerService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E1, R2, R3, T1, T2, H1, K4 (integration half): the worker against isolated Testcontainers PostgreSQL and Kafka, an
 * isolated topic and consumer group, and a scripted provider. No live email, no development services.
 */
@Import(ScriptedProviderConfig.class)
@TestPropertySource(properties = {
        "notify.worker.email.enabled=true",
        "notify.worker.concurrency.email=1",
        "notify.worker.email.retry-interval=100ms",
        "notify.worker.email.pause-initial=300ms",
        "notify.worker.email.pause-max=1s",
        "notify.worker.email.health-poll-interval=200ms",
        "notify.retry.base-delay=2s",
        "notify.retry.max-delay=10s",
        "notify.mail.from=noreply@worker.notify.local"
})
class EmailWorkerIntegrationTest extends AbstractKafkaIntegrationTest {

    static final String TOPIC = WorkerKafkaSupport.uniqueTopic("notify.test.email.e1");
    static final String DLT = TOPIC + ".DLT";
    static final String GROUP = "grp-" + TOPIC;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(60);

    static {
        try {
            WorkerKafkaSupport.createTopics(kafka.getBootstrapServers(), 3, TOPIC, DLT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void workerProperties(DynamicPropertyRegistry registry) {
        registry.add("notify.worker.email.topic", () -> TOPIC);
        registry.add("notify.worker.email.group-id", () -> GROUP);
    }

    @Autowired private ScriptedEmailProvider provider;
    @Autowired private NotificationService notificationService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Scheduler scheduler;
    @Autowired private WorkerContainerController controller;
    @Autowired private EmailWorkerProperties workerProperties;
    @Autowired private com.notify.delivery.worker.control.EmailWorkerHealthIndicator healthIndicator;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        provider.reset();
        tenantId = tenantRepository.save(new TenantEntity("WorkerOrg", "worker-org-" + UUID.randomUUID())).getId();
    }

    // ─── fixtures ──────────────────────────────────────────────────────────────────────────────────────────────────

    private String bootstrap() {
        return kafka.getBootstrapServers();
    }

    /** Inserts a QUEUED EMAIL notification at the given generation and returns its id. */
    private UUID seedQueued(int generation, int maxAttempts, String traceContext) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body, dispatch_generation,
                    delivery_cycle, attempt_count, max_attempts, trace_context, queued_at)
                VALUES (?, ?, 'EMAIL', 'QUEUED', 'rcpt@worker.notify.local', 'subject', 'body', ?, 1, 0, ?, ?, now())
                """, id, tenantId, generation, maxAttempts, traceContext);
        return id;
    }

    private void publishEvent(UUID notificationId, int generation) throws Exception {
        WorkerKafkaSupport.publish(bootstrap(), TOPIC, notificationId.toString(),
                WorkerKafkaSupport.dispatchEvent(tenantId, notificationId, generation, "NOTIFICATION_QUEUED"));
    }

    /** Dead-letter records for one notification key (the DLT topic is shared by every test in this class). */
    private List<ConsumerRecord<String, byte[]>> deadLetteredFor(UUID id) {
        return WorkerKafkaSupport.readAll(bootstrap(), DLT).stream().filter(r -> id.toString().equals(r.key())).toList();
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

    private Map<String, Object> snapshot(UUID id) {
        return jdbc.queryForMap("SELECT to_jsonb(n)::text AS img FROM notifications n WHERE id = ?", id);
    }

    private List<Map<String, Object>> attempts(UUID id) {
        return jdbc.queryForList("SELECT * FROM delivery_attempts WHERE notification_id = ? ORDER BY attempt_number", id);
    }

    private List<Map<String, Object>> dlqRows(UUID id) {
        return jdbc.queryForList("SELECT * FROM outbox_events WHERE aggregate_id = ? AND topic = 'notify.dlq.v1'", id);
    }

    // ─── E1 ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("E1 an accepted notification travels outbox payload -> Kafka -> worker -> provider -> DELIVERED, then the offset is committed")
    void acceptedNotificationIsDelivered() throws Exception {
        CreateNotificationRequest request = new CreateNotificationRequest(Channel.EMAIL, "person@worker.notify.local",
                "Hello", "World", null, null, null, null, null, null);
        UUID id = notificationService.acceptNotification(tenantId, request, null).notificationId();
        // The exact envelope the real acceptance path writes to the outbox (proves worker/producer contract compatibility).
        String payload = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE aggregate_id = ?", String.class, id);
        WorkerKafkaSupport.publish(bootstrap(), TOPIC, id.toString(), payload);

        awaitStatus(id, "DELIVERED");
        awaitAllCommitted();

        assertThat(provider.calls()).isEqualTo(1);
        var sent = provider.sent().get(0);
        assertThat(sent.from()).isEqualTo("noreply@worker.notify.local");
        assertThat(sent.to()).isEqualTo("person@worker.notify.local");
        assertThat(sent.subject()).isEqualTo("Hello");
        assertThat(sent.text()).isEqualTo("World");
        assertThat(sent.idempotencyKey()).isEqualTo(id + "-1");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, provider, provider_message_id, attempt_count, claim_seq, sender FROM notifications WHERE id = ?", id);
        assertThat(row.get("provider")).isEqualTo("scripted");
        assertThat(row.get("provider_message_id")).isEqualTo("scripted_" + id + "-1");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("claim_seq")).intValue()).isEqualTo(1);
        assertThat(row.get("sender")).isEqualTo("noreply@worker.notify.local");

        List<Map<String, Object>> attempts = attempts(id);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).get("outcome")).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForList("SELECT to_status FROM notification_status_history WHERE notification_id = ? ORDER BY id",
                String.class, id)).containsExactly("QUEUED", "PROCESSING", "DELIVERED");
        assertThat(deadLetteredFor(id)).isEmpty();
    }

    @Test
    @DisplayName("E1 a duplicate record after the terminal state is acknowledged without a second provider call")
    void duplicateAfterTerminal() throws Exception {
        UUID id = seedQueued(1, 5, null);
        publishEvent(id, 1);
        awaitStatus(id, "DELIVERED");
        awaitAllCommitted();
        Map<String, Object> before = snapshot(id);

        publishEvent(id, 1);   // duplicate delivery of the same event
        awaitAllCommitted();

        assertThat(provider.callsFor(id)).isEqualTo(1);
        assertThat(snapshot(id)).isEqualTo(before);
    }

    @Test
    @DisplayName("E1 a stale generation (event older than the row) is discarded and acknowledged; the row is untouched")
    void staleGeneration() throws Exception {
        UUID id = seedQueued(5, 5, null);
        Map<String, Object> before = snapshot(id);

        publishEvent(id, 4);
        awaitAllCommitted();

        assertThat(provider.callsFor(id)).isZero();
        assertThat(snapshot(id)).isEqualTo(before);
    }

    @Test
    @DisplayName("E1 an event for an unknown notification is discarded and acknowledged (never dead-lettered)")
    void unknownNotification() throws Exception {
        UUID unknown = UUID.randomUUID();
        publishEvent(unknown, 1);
        awaitAllCommitted();
        assertThat(provider.callsFor(unknown)).isZero();
        assertThat(deadLetteredFor(unknown)).isEmpty();
    }

    @Test
    @DisplayName("R2/E1 a retryable failure: RETRYING with the backoff time, RETRYABLE attempt, Quartz retry trigger keeps the trace context")
    void retryableFailureSchedulesTrigger() throws Exception {
        String trace = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        UUID id = seedQueued(2, 5, trace);
        provider.script(r -> new DeliveryResult.RetryableFailure("PROVIDER_UNAVAILABLE", "down", 503, null));

        publishEvent(id, 2);
        awaitStatus(id, "RETRYING");
        awaitAllCommitted();

        Map<String, Object> row = jdbc.queryForMap("SELECT next_retry_at, last_error_code, attempt_count FROM notifications WHERE id = ?", id);
        Instant next = ((Timestamp) row.get("next_retry_at")).toInstant();
        Instant finished = ((Timestamp) attempts(id).get(0).get("finished_at")).toInstant();
        assertThat(Duration.between(finished, next).toMillis()).as("base delay 2 s +/- 20 %").isBetween(1_500L, 2_600L);
        assertThat(row.get("last_error_code")).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(attempts(id)).hasSize(1);
        assertThat(attempts(id).get(0).get("outcome")).isEqualTo("RETRYABLE_FAILURE");

        JobDetail job = scheduler.getJobDetail(JobKey.jobKey("retry-" + id, NotificationSchedulerService.RETRY_GROUP));
        assertThat(job).as("retry trigger scheduled after the commit").isNotNull();
        assertThat(job.getJobDataMap().getString("traceparent")).isEqualTo(trace);
        assertThat(job.getJobDataMap().getInt("expectedGeneration")).isEqualTo(2);
        assertThat(job.getJobDataMap().getString("notificationId")).isEqualTo(id.toString());
    }

    @Test
    @DisplayName("R2 exhaustion: the last allowed attempt failing retryably gives FAILED + RETRYABLE attempt + MAX_RETRIES_EXCEEDED dead-letter event and no trigger")
    void exhaustion() throws Exception {
        UUID id = seedQueued(1, 1, null);
        provider.script(r -> new DeliveryResult.RetryableFailure("RATE_LIMITED", "quota", 429, null));

        publishEvent(id, 1);
        awaitStatus(id, "FAILED");
        awaitAllCommitted();

        assertThat(attempts(id)).hasSize(1);
        assertThat(attempts(id).get(0).get("outcome")).isEqualTo("RETRYABLE_FAILURE");
        List<Map<String, Object>> dlq = dlqRows(id);
        assertThat(dlq).hasSize(1);
        JsonNode payload = MAPPER.readTree(dlq.get(0).get("payload").toString());
        assertThat(payload.get("reason").asText()).isEqualTo("MAX_RETRIES_EXCEEDED");
        assertThat(payload.get("lastErrorCode").asText()).isEqualTo("RATE_LIMITED");
        assertThat(scheduler.getJobDetail(JobKey.jobKey("retry-" + id, NotificationSchedulerService.RETRY_GROUP))).isNull();
        assertThat(jdbc.queryForObject("SELECT next_retry_at FROM notifications WHERE id = ?", Timestamp.class, id)).isNull();
    }

    @Test
    @DisplayName("R2 a permanent failure is FAILED with a PERMANENT attempt and a PERMANENT_FAILURE dead-letter event")
    void permanentFailure() throws Exception {
        UUID id = seedQueued(1, 5, null);
        provider.script(r -> new DeliveryResult.PermanentFailure("VALIDATION_FAILED", "bad", 422, null));

        publishEvent(id, 1);
        awaitStatus(id, "FAILED");
        awaitAllCommitted();

        assertThat(attempts(id).get(0).get("outcome")).isEqualTo("PERMANENT_FAILURE");
        JsonNode payload = MAPPER.readTree(dlqRows(id).get(0).get("payload").toString());
        assertThat(payload.get("reason").asText()).isEqualTo("PERMANENT_FAILURE");
    }

    @Test
    @DisplayName("R3 a deferral returns to RETRYING, refunds the attempt, counts the deferral, writes no attempt row, and schedules the trigger")
    void deferral() throws Exception {
        UUID id = seedQueued(1, 5, null);
        provider.script(r -> new DeliveryResult.Deferred("FAKE_DEFERRED", Duration.ofSeconds(30)));

        publishEvent(id, 1);
        awaitStatus(id, "RETRYING");
        awaitAllCommitted();

        Map<String, Object> row = jdbc.queryForMap("SELECT attempt_count, deferral_count, claim_seq FROM notifications WHERE id = ?", id);
        assertThat(((Number) row.get("attempt_count")).intValue()).isZero();
        assertThat(((Number) row.get("deferral_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("claim_seq")).intValue()).isEqualTo(1);
        assertThat(attempts(id)).isEmpty();
        assertThat(scheduler.getJobDetail(JobKey.jobKey("retry-" + id, NotificationSchedulerService.RETRY_GROUP))).isNotNull();
    }

    @Test
    @DisplayName("T1 a suspended tenant: no provider call, FAILED, system attempt, TENANT_NOT_ACTIVE dead-letter event")
    void suspendedTenantIsBlockedBeforeSend() throws Exception {
        UUID id = seedQueued(1, 5, null);
        jdbc.update("UPDATE tenants SET status = ? WHERE id = ?", TenantStatus.SUSPENDED.name(), tenantId);

        publishEvent(id, 1);
        awaitStatus(id, "FAILED");
        awaitAllCommitted();

        assertThat(provider.callsFor(id)).isZero();
        assertThat(attempts(id).get(0).get("provider")).isEqualTo("notify-system");
        JsonNode payload = MAPPER.readTree(dlqRows(id).get(0).get("payload").toString());
        assertThat(payload.get("reason").asText()).isEqualTo("TENANT_NOT_ACTIVE");
    }

    @Test
    @DisplayName("T2 a tenant suspended while the send is in flight: the send finishes and its result is persisted normally")
    void suspensionMidSendDoesNotCancelTheSend() throws Exception {
        UUID id = seedQueued(1, 5, null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        provider.script(r -> {
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new DeliveryResult.Success("late_ok");
        });

        publishEvent(id, 1);
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
        jdbc.update("UPDATE tenants SET status = 'SUSPENDED' WHERE id = ?", tenantId);
        release.countDown();

        awaitStatus(id, "DELIVERED");
        assertThat(attempts(id).get(0).get("outcome")).isEqualTo("SUCCESS");
        assertThat(dlqRows(id)).isEmpty();
    }

    @Test
    @DisplayName("H1 a historical QUEUED notification with a PUBLISHED outbox row and no Kafka event is never touched, delivered or adopted")
    void historicalQueuedRowIsUntouched() throws Exception {
        UUID historical = seedQueued(1, 5, null);
        jdbc.update("""
                INSERT INTO outbox_events (id, tenant_id, aggregate_type, aggregate_id, event_type, topic, message_key, payload,
                                           status, attempts, published_at, created_at)
                VALUES (?, ?, 'NOTIFICATION', ?, 'NOTIFICATION_QUEUED', 'notify.dispatch.email.v1', ?, cast('{"v":1}' as jsonb),
                        'PUBLISHED', 1, now(), now())
                """, UUID.randomUUID(), tenantId, historical, historical.toString());
        Map<String, Object> notificationBefore = snapshot(historical);
        List<Map<String, Object>> outboxBefore = jdbc.queryForList("SELECT to_jsonb(o)::text AS img FROM outbox_events o WHERE aggregate_id = ?", historical);

        // a full delivery run of an unrelated notification while the worker is running
        UUID other = seedQueued(1, 5, null);
        publishEvent(other, 1);
        awaitStatus(other, "DELIVERED");
        awaitAllCommitted();
        Thread.sleep(2_000);   // give a hypothetical scanner time to act

        assertThat(provider.callsFor(historical)).isZero();
        assertThat(snapshot(historical)).isEqualTo(notificationBefore);
        assertThat(jdbc.queryForList("SELECT to_jsonb(o)::text AS img FROM outbox_events o WHERE aggregate_id = ?", historical)).isEqualTo(outboxBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_status_history WHERE notification_id = ?", Integer.class, historical)).isZero();
    }

    // ─── K4 (integration half) ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("K4 a poison record is dead-lettered byte-identical with the reason header, committed only afterwards, and does not block the next record")
    void poisonIsDeadLetteredAndDoesNotBlock() throws Exception {
        byte[] poison = "{ this is not json é".getBytes(StandardCharsets.UTF_8);
        String poisonKey = "poison-" + UUID.randomUUID();
        WorkerKafkaSupport.publish(bootstrap(), TOPIC, poisonKey, poison, Map.of("traceparent", "00-aa-bb-01"));

        UUID id = seedQueued(1, 5, null);
        publishEvent(id, 1);   // a valid record behind the poison one on (possibly) the same partition
        awaitStatus(id, "DELIVERED");
        awaitAllCommitted();

        List<ConsumerRecord<String, byte[]>> dead = WorkerKafkaSupport.readAll(bootstrap(), DLT).stream()
                .filter(r -> poisonKey.equals(r.key())).toList();
        assertThat(dead).hasSize(1);
        ConsumerRecord<String, byte[]> dlt = dead.get(0);
        assertThat(dlt.value()).isEqualTo(poison);
        assertThat(new String(dlt.headers().lastHeader("x-notify-dlt-reason").value(), StandardCharsets.UTF_8)).isEqualTo("POISON_PAYLOAD");
        assertThat(new String(dlt.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8)).isEqualTo("00-aa-bb-01");
        assertThat(dlt.headers().lastHeader("kafka_dlt-exception-message")).isNull();
        assertThat(dlt.headers().lastHeader("kafka_dlt-exception-stacktrace")).isNull();
        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("the running worker reports UP with partitions assigned and a real consumer-lag reading once records were fetched")
    void healthyWorkerIsUp() throws Exception {
        UUID id = seedQueued(1, 5, null);
        publishEvent(id, 1);
        awaitStatus(id, "DELIVERED");
        awaitAllCommitted();

        await().atMost(WAIT).untilAsserted(() -> {
            var snapshot = controller.snapshot();
            assertThat(snapshot.containerRunning()).isTrue();
            assertThat(snapshot.assignedPartitions()).isPositive();
            assertThat(snapshot.stopped()).isFalse();
            assertThat(snapshot.maxLag()).as("records-lag-max read from the consumer metrics").isNotNaN().isGreaterThanOrEqualTo(0);
        });
        var health = healthIndicator.health();
        assertThat(health.getStatus()).isEqualTo(org.springframework.boot.health.contributor.Status.UP);
        assertThat(health.getDetails()).containsKeys("assignedPartitions", "maxLag", "lagThreshold", "pausedPartitions");
        assertThat(workerProperties.enabled()).isTrue();
    }
}
