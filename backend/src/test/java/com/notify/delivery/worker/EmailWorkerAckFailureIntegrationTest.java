package com.notify.delivery.worker;

import com.notify.AbstractKafkaIntegrationTest;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.provider.EmailSendRequest;
import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.delivery.worker.classifier.ErrorCategory;
import com.notify.delivery.worker.control.WorkerContainerController;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.RetriableCommitFailedException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * K8 (integration half): the offset commit (acknowledgment) fails <em>after</em> the result transaction has durably
 * committed. The real listener, the real {@code DefaultErrorHandler} and recoverer wiring, the real worker beans, an
 * isolated Testcontainers PostgreSQL and Kafka broker, an isolated topic and consumer group, and a scripted provider.
 *
 * <p><b>What is injected, precisely.</b> The fault is injected at the consumer client's offset-commit call: a test-scoped
 * {@code ConsumerPostProcessor} wraps the consumer the production {@code emailConsumerFactory} creates, and its
 * {@code commitSync(Map, Duration)} throws {@link RetriableCommitFailedException} for the target record's offset. Spring's
 * {@code Acknowledgment.acknowledge()}, the container's own commit retry ({@code commitRetries=1}), the worker's
 * {@code acknowledge()} wrapper and the error handler are all production code that run unmodified above that call.
 * <b>This is not an actual broker failure or outage</b>: the broker is healthy throughout, and the same wrapper hands the
 * redelivery's commit through to the real broker. A second, cheap observation hook counts deliveries per
 * {@code poll} and, for the redelivery's commit, holds the consumer thread at a gate so the state can be inspected while
 * the commit is still pending (no timing assumptions).
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
        "notify.mail.from=noreply@ack.notify.local"
})
class EmailWorkerAckFailureIntegrationTest extends AbstractKafkaIntegrationTest {

    static final String TOPIC = WorkerKafkaSupport.uniqueTopic("notify.test.email.k8");
    static final String DLT = TOPIC + ".DLT";
    static final String GROUP = "grp-" + TOPIC;
    static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    static final String FAULT_MESSAGE = "test fault: offset commit failed after the result transaction committed";
    static final AckFaultInjector INJECTOR = new AckFaultInjector();
    private static final Duration WAIT = Duration.ofSeconds(60);

    static {
        try {
            WorkerKafkaSupport.createTopics(kafka.getBootstrapServers(), 1, TOPIC, DLT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void workerProperties(DynamicPropertyRegistry registry) {
        registry.add("notify.worker.email.topic", () -> TOPIC);
        registry.add("notify.worker.email.group-id", () -> GROUP);
    }

    /** Installs the fault injector on the production consumer factory and the observer on the production error handler. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FaultInjectionConfig {

        @Bean
        static BeanPostProcessor ackFaultInstaller() {
            return new BeanPostProcessor() {
                @Override
                @SuppressWarnings("unchecked")
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if ("emailConsumerFactory".equals(beanName) && bean instanceof DefaultKafkaConsumerFactory<?, ?> factory) {
                        ((DefaultKafkaConsumerFactory<String, byte[]>) factory).addPostProcessor(INJECTOR::wrap);
                    }
                    if ("emailWorkerErrorHandler".equals(beanName) && bean instanceof DefaultErrorHandler handler) {
                        handler.setRetryListeners(INJECTOR.retryObserver());
                    }
                    return bean;
                }
            };
        }
    }

    @Autowired private ScriptedEmailProvider provider;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkerContainerController controller;
    @Autowired private DeliveryExceptionClassifier classifier;
    @Autowired(required = false) private MeterRegistry meters;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        provider.reset();
        INJECTOR.disarm();
        tenantId = tenantRepository.save(new TenantEntity("AckOrg", "ack-org-" + UUID.randomUUID())).getId();
    }

    @AfterEach
    void releaseGate() {
        INJECTOR.disarm();   // never leave the consumer thread parked at the gate, even when an assertion failed
    }

    // ─── fixtures ──────────────────────────────────────────────────────────────────────────────────────────────────

    private String bootstrap() {
        return kafka.getBootstrapServers();
    }

    private UUID seedQueued() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body, dispatch_generation,
                    delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, 'EMAIL', 'QUEUED', 'rcpt@ack.notify.local', 'subject', 'body', 1, 1, 0, 5, now())
                """, id, tenantId);
        return id;
    }

    private long publish(UUID id) throws Exception {
        return WorkerKafkaSupport.publish(bootstrap(), TOPIC, id.toString(),
                WorkerKafkaSupport.dispatchEvent(tenantId, id, 1, "NOTIFICATION_QUEUED").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Map.of());
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id);
    }

    private Long committedOffset() throws Exception {
        return WorkerKafkaSupport.committed(bootstrap(), GROUP, TOPIC).get(PARTITION);
    }

    private void awaitCommitted(long offset) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(committedOffset()).isEqualTo(offset));
    }

    private double count(String name, String... tags) {
        assertThat(meters).as("a MeterRegistry bean is required to observe the worker counters").isNotNull();
        return meters.find(name).tags(tags).counters().stream().mapToDouble(Counter::count).sum();
    }

    private long deadLetterRecordsFor(UUID id) {
        return WorkerKafkaSupport.readAll(bootstrap(), DLT).stream().filter(r -> id.toString().equals(r.key())).count();
    }

    /** Everything the result transaction writes for one notification, read over its own connection (committed data only). */
    record Evidence(String notification, List<String> history, List<String> attempts, List<String> outbox) {
    }

    static Evidence evidence(UUID id) {
        try (Connection c = postgres.createConnection("")) {
            return new Evidence(
                    one(c, "SELECT to_jsonb(n)::text FROM notifications n WHERE id = ?", id),
                    many(c, "SELECT to_status FROM notification_status_history WHERE notification_id = ? ORDER BY id", id),
                    many(c, "SELECT to_jsonb(a)::text FROM delivery_attempts a WHERE notification_id = ? ORDER BY attempt_number", id),
                    many(c, "SELECT to_jsonb(o)::text FROM outbox_events o WHERE aggregate_id = ? ORDER BY created_at, id", id));
        } catch (Exception e) {
            throw new IllegalStateException("evidence query failed", e);
        }
    }

    private static String one(Connection c, String sql, UUID id) throws Exception {
        List<String> rows = many(c, sql, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static List<String> many(Connection c, String sql, UUID id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        }
    }

    // ─── the scenario ──────────────────────────────────────────────────────────────────────────────────────────────

    private record Scenario(Function<EmailSendRequest, DeliveryResult> targetResult, String terminalStatus,
                            String resultCounter, int dlqOutboxRows, String attemptOutcome) {
    }

    /**
     * warm record (healthy, committed) -> target record whose acknowledgment fails after its result committed -> record
     * behind it on the same partition.
     */
    private void runScenario(Scenario s) throws Exception {
        UUID warm = seedQueued();
        UUID target = seedQueued();
        UUID after = seedQueued();

        // 1. A healthy record first: the group has a committed offset, so "not advanced" is a statement about a real number.
        long warmOffset = publish(warm);
        await().atMost(WAIT).untilAsserted(() -> assertThat(status(warm)).isEqualTo("DELIVERED"));
        awaitCommitted(warmOffset + 1);

        // 2. Arm the fault for the target record only and script its provider outcome.
        provider.script(r -> r.idempotencyKey().startsWith(target.toString())
                ? s.targetResult().apply(r) : new DeliveryResult.Success("ok_" + r.idempotencyKey()));
        INJECTOR.arm(target.toString(), () -> evidence(target));
        double resultsBefore = count("notify.delivery.results", "outcome", s.resultCounter());
        double discardedBefore = count("notify.delivery.results", "outcome", "discarded");
        double pausesBefore = count("notify.worker.pauses");
        double quarantinedBefore = count("notify.worker.quarantined");
        double stopsBefore = count("notify.worker.stops");

        long targetOffset = publish(target);
        assertThat(targetOffset).as("the target record follows the warm record on the single partition").isEqualTo(warmOffset + 1);

        // 3. The fault fires on the first delivery; the redelivery then reaches its (held) acknowledgment.
        assertThat(INJECTOR.gateReached.await(WAIT.toSeconds(), TimeUnit.SECONDS))
                .as("the redelivery of the same record reached the acknowledgment").isTrue();
        long afterOffset = publish(after);

        // ── state while the redelivery's acknowledgment is pending ──
        // (a) the failure was injected after the result was durable: the independent-connection reading taken at the
        //     moment of the first failed commit already shows every write of the result transaction.
        assertThat(INJECTOR.failedCommitCalls.get()).as("first delivery: the commit and the container's one retry both failed").isEqualTo(2);
        assertThat(INJECTOR.evidenceAtFailure).isNotEmpty();
        Evidence atFailure = INJECTOR.evidenceAtFailure.get(0);
        // (the fixture inserts the QUEUED row directly, so the history holds only what the worker wrote)
        assertThat(atFailure.history()).containsExactly("PROCESSING", s.terminalStatus());
        assertThat(atFailure.attempts()).hasSize(1).first().asString().contains(s.attemptOutcome());
        assertThat(atFailure.outbox()).as("business outbox rows written by the result transaction").hasSize(s.dlqOutboxRows());
        assertThat(atFailure.notification()).contains("\"status\": \"" + s.terminalStatus() + "\"");
        // all failed attempts saw the same durable state
        assertThat(INJECTOR.evidenceAtFailure).allSatisfy(e -> assertThat(e).isEqualTo(atFailure));

        // (b) the failed acknowledgment did not advance the group offset past the record
        assertThat(committedOffset()).as("group offset stays at the target record").isEqualTo(targetOffset);

        // (c) the same record was redelivered through the real listener path
        assertThat(INJECTOR.deliveries.get()).isEqualTo(2);
        assertThat(count("notify.delivery.results", "outcome", s.resultCounter()) - resultsBefore)
                .as("the result was produced exactly once").isEqualTo(1.0);
        assertThat(count("notify.delivery.results", "outcome", "discarded") - discardedBefore)
                .as("the redelivery met the terminal row and was discarded by the disposition table").isEqualTo(1.0);

        // (d) the error followed BROKER_TRANSIENT handling and nothing else
        assertThat(INJECTOR.failures()).hasSize(1);
        RetryFailure failure = INJECTOR.failures().get(0);
        assertThat(failure.key()).isEqualTo(target.toString());
        assertThat(failure.deliveryAttempt()).isEqualTo(1);
        assertThat(classifier.classify(failure.exception())).isEqualTo(ErrorCategory.BROKER_TRANSIENT);
        assertThat(failure.exception()).hasRootCauseInstanceOf(RetriableCommitFailedException.class);
        assertThat(INJECTOR.recovered.get()).as("recoverer never invoked").isZero();
        assertThat(INJECTOR.recoveryFailed.get()).isZero();

        // (e) no redelivery side effect: no second send, no second write of any kind
        assertThat(provider.callsFor(target)).isEqualTo(1);
        assertThat(evidence(target)).isEqualTo(atFailure);

        // (f) no inappropriate dead-lettering, pause, quarantine or stop
        assertThat(deadLetterRecordsFor(target)).isZero();
        assertThat(controller.pausedPartitions()).isEmpty();
        assertThat(controller.snapshot().stopped()).isFalse();
        assertThat(count("notify.worker.pauses") - pausesBefore).isZero();
        assertThat(count("notify.worker.quarantined") - quarantinedBefore).isZero();
        assertThat(count("notify.worker.stops") - stopsBefore).isZero();

        // (g) a failed acknowledgment blocks the records behind it on the partition (nothing is skipped)
        assertThat(status(after)).isEqualTo("QUEUED");
        assertThat(provider.callsFor(after)).isZero();

        // ── release the held acknowledgment: the real broker commit succeeds ──
        INJECTOR.release();
        awaitCommitted(afterOffset + 1);
        await().atMost(WAIT).untilAsserted(() -> assertThat(status(after)).isEqualTo("DELIVERED"));

        // the offset advanced past the target only through a successful acknowledgment of its redelivery
        assertThat(INJECTOR.passedCommitCalls.get()).isGreaterThanOrEqualTo(1);
        assertThat(INJECTOR.deliveries.get()).as("the record was delivered exactly twice in total").isEqualTo(2);
        assertThat(INJECTOR.failures()).hasSize(1);
        assertThat(INJECTOR.recovered.get()).isZero();
        assertThat(INJECTOR.recoveryFailed.get()).isZero();
        assertThat(provider.callsFor(target)).isEqualTo(1);
        assertThat(provider.callsFor(after)).isEqualTo(1);
        assertThat(evidence(target)).as("no duplicate result, history, attempt or outbox write after the final acknowledgment")
                .isEqualTo(atFailure);
        assertThat(status(target)).isEqualTo(s.terminalStatus());
        assertThat(deadLetterRecordsFor(target)).isZero();
        assertThat(count("notify.worker.pauses") - pausesBefore).isZero();
        assertThat(controller.snapshot().stopped()).isFalse();
    }

    @Test
    @DisplayName("K8 ack failure after a DELIVERED result: offset not advanced, BROKER_TRANSIENT, same record reprocessed, no second send or write, offset advances afterwards")
    void commitFailureAfterDeliveredResult() throws Exception {
        runScenario(new Scenario(r -> new DeliveryResult.Success("provider_ok"), "DELIVERED", "delivered", 0, "SUCCESS"));
    }

    @Test
    @DisplayName("K8 ack failure after a FAILED result (status, history, attempt and dead-letter outbox row all written): redelivery duplicates none of them")
    void commitFailureAfterFailedResult() throws Exception {
        runScenario(new Scenario(r -> new DeliveryResult.PermanentFailure("VALIDATION_FAILED", "bad", 422, null),
                "FAILED", "failed", 1, "PERMANENT_FAILURE"));
    }

    // ─── injector ──────────────────────────────────────────────────────────────────────────────────────────────────

    record RetryFailure(String key, long offset, Exception exception, int deliveryAttempt) {
    }

    /**
     * Wraps the consumer. For the armed target key: every {@code commitSync} of the target's offset fails while the
     * target has been delivered once; from the redelivery on, the first commit parks at {@link #release} and then goes to
     * the real broker.
     */
    static final class AckFaultInjector {

        private volatile String targetKey;
        private volatile long targetOffset = -1;
        private volatile Supplier<Evidence> evidenceProbe;
        private volatile CountDownLatch release = new CountDownLatch(0);

        volatile CountDownLatch gateReached = new CountDownLatch(1);
        final AtomicInteger deliveries = new AtomicInteger();
        final AtomicInteger failedCommitCalls = new AtomicInteger();
        final AtomicInteger passedCommitCalls = new AtomicInteger();
        final AtomicInteger recovered = new AtomicInteger();
        final AtomicInteger recoveryFailed = new AtomicInteger();
        final List<Evidence> evidenceAtFailure = new CopyOnWriteArrayList<>();
        private final List<RetryFailure> failures = new CopyOnWriteArrayList<>();

        void arm(String key, Supplier<Evidence> probe) {
            disarm();
            targetOffset = -1;
            deliveries.set(0);
            failedCommitCalls.set(0);
            passedCommitCalls.set(0);
            recovered.set(0);
            recoveryFailed.set(0);
            evidenceAtFailure.clear();
            failures.clear();
            evidenceProbe = probe;
            gateReached = new CountDownLatch(1);
            release = new CountDownLatch(1);
            targetKey = key;
        }

        void release() {
            release.countDown();
        }

        void disarm() {
            targetKey = null;
            release.countDown();
        }

        List<RetryFailure> failures() {
            return List.copyOf(failures);
        }

        RetryListener retryObserver() {
            return new RetryListener() {
                @Override
                public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
                    failures.add(new RetryFailure(String.valueOf(record.key()), record.offset(), ex, deliveryAttempt));
                }

                @Override
                public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
                    recovered.incrementAndGet();
                }

                @Override
                public void recoveryFailed(ConsumerRecord<?, ?> record, Exception original, Exception failure) {
                    recoveryFailed.incrementAndGet();
                }
            };
        }

        @SuppressWarnings("unchecked")
        Consumer<String, byte[]> wrap(Consumer<String, byte[]> delegate) {
            return (Consumer<String, byte[]>) Proxy.newProxyInstance(Consumer.class.getClassLoader(),
                    new Class<?>[]{Consumer.class}, (proxy, method, args) -> invoke(delegate, method, args));
        }

        private Object invoke(Consumer<String, byte[]> delegate, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("commitSync".equals(name) && args != null && args.length > 0
                    && args[0] instanceof Map<?, ?> offsets && isTargetCommit(offsets)) {
                interceptTargetCommit();
            }
            Object result;
            try {
                result = method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if ("poll".equals(name) && result instanceof ConsumerRecords<?, ?> records) {
                observeDeliveries(records);
            }
            return result;
        }

        private boolean isTargetCommit(Map<?, ?> offsets) {
            long expected = targetOffset;
            return targetKey != null && expected >= 0
                    && offsets.get(PARTITION) instanceof OffsetAndMetadata committed && committed.offset() == expected + 1;
        }

        private void interceptTargetCommit() throws InterruptedException {
            if (deliveries.get() <= 1) {
                failedCommitCalls.incrementAndGet();
                Supplier<Evidence> probe = evidenceProbe;
                if (probe != null) {
                    evidenceAtFailure.add(probe.get());   // read over a separate connection: only committed data is visible
                }
                throw new RetriableCommitFailedException(FAULT_MESSAGE);
            }
            gateReached.countDown();
            if (!release.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test gate was never released");
            }
            passedCommitCalls.incrementAndGet();
        }

        private void observeDeliveries(ConsumerRecords<?, ?> records) {
            String key = targetKey;
            if (key == null) {
                return;
            }
            for (ConsumerRecord<?, ?> r : records.records(PARTITION)) {
                if (key.equals(r.key())) {
                    targetOffset = r.offset();
                    deliveries.incrementAndGet();
                }
            }
        }
    }
}
