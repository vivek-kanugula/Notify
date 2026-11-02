package com.notify.delivery.worker.config;

import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.delivery.worker.classifier.ErrorCategory;
import com.notify.delivery.worker.control.WorkerContainerController;
import com.notify.delivery.worker.exception.WorkerExceptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * K3 / K4: category to retries and recovery; a recoverer that throws commits nothing, one that returns normally
 * commits; dead-letter record is byte-identical with sanitized headers; a failed DLT send commits nothing.
 * Uses the production wiring ({@link WorkerRecoverySupport#buildErrorHandler}) against a stub consumer.
 */
class EmailWorkerRecoveryConfigTest {

    private static final EmailWorkerProperties PROPS = new EmailWorkerProperties(
            true, "notify.dispatch.email.v1", "g", false,
            Duration.ofMillis(1), 3,
            Duration.ofMillis(30), Duration.ofMillis(120), 3,
            Duration.ofMillis(10), 2, Duration.ofSeconds(1), 1000, Duration.ofSeconds(60));

    private final DeliveryExceptionClassifier classifier = new DeliveryExceptionClassifier();

    // ─── fakes ─────────────────────────────────────────────────────────────────────────────────────────────────────

    static final class FakeContainer implements WorkerContainerController.ContainerAccess {
        final List<String> calls = new CopyOnWriteArrayList<>();
        /** Counted down when the stopper thread enters {@link #stop}, before the optional gate. */
        final CountDownLatch stopEntered = new CountDownLatch(1);
        /** When set, {@link #stop} does not complete (and does not record "stop") until the gate opens. */
        volatile CountDownLatch stopGate;
        volatile String stopThread;

        @Override
        public void pause(TopicPartition partition) {
            calls.add("pause:" + partition);
        }

        @Override
        public void resume(TopicPartition partition) {
            calls.add("resume:" + partition);
        }

        @Override
        public void stop(Runnable onStopped) {
            stopThread = Thread.currentThread().getName();
            stopEntered.countDown();
            CountDownLatch gate = stopGate;
            if (gate != null) {
                try {
                    if (!gate.await(10, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            calls.add("stop");
            onStopped.run();
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public Collection<TopicPartition> assignedPartitions() {
            return List.of();
        }

        @Override
        public double maxLag() {
            return Double.NaN;
        }
    }

    private final FakeContainer container = new FakeContainer();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService stopper = Executors.newSingleThreadScheduledExecutor();
    private WorkerContainerController controller;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<String> consumerCalls = new CopyOnWriteArrayList<>();
    private final List<ConsumerRecord<?, ?>> deadLettered = new CopyOnWriteArrayList<>();
    private volatile boolean deadLetterFails;

    @BeforeEach
    void setUp() {
        controller = new WorkerContainerController(container, PROPS, () -> true, meters,
                Clock.systemUTC(), timers, stopper, false);
    }

    @AfterEach
    void tearDown() {
        timers.shutdownNow();
        stopper.shutdownNow();
    }

    private DefaultErrorHandler handler() {
        WorkerRecoverySupport.Recoverer recoverer = new WorkerRecoverySupport.Recoverer(classifier, controller, (record, ex) -> {
            if (deadLetterFails) {
                throw new IllegalStateException("dlt send failed");
            }
            deadLettered.add(record);
        });
        return WorkerRecoverySupport.buildErrorHandler(recoverer, classifier, PROPS);
    }

    @SuppressWarnings("unchecked")
    private Consumer<?, ?> stubConsumer() {
        return (Consumer<?, ?>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Consumer.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "seek" -> consumerCalls.add("seek:" + args[0] + ":" + args[1]);
                case "commitSync", "commitAsync" -> consumerCalls.add("commit:" + args[0]);
                default -> {
                }
            }
            Class<?> r = method.getReturnType();
            if (r == boolean.class) return false;
            if (r == long.class) return 0L;
            if (r == int.class) return 0;
            return null;
        });
    }

    private MessageListenerContainer stubContainer() {
        ContainerProperties cp = new ContainerProperties("notify.dispatch.email.v1");
        cp.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return (MessageListenerContainer) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{MessageListenerContainer.class}, (proxy, method, args) -> {
            if (method.getName().equals("getContainerProperties")) return cp;
            if (method.getName().equals("isRunning")) return true;
            Class<?> r = method.getReturnType();
            if (r == boolean.class) return false;
            if (r == long.class) return 0L;
            if (r == int.class) return 0;
            return null;
        });
    }

    private final ConsumerRecord<String, byte[]> record =
            new ConsumerRecord<>("notify.dispatch.email.v1", 2, 17L, "key-1", "payload".getBytes(StandardCharsets.UTF_8));

    /** Delivers the same failure until the handler stops retrying in place; returns the outcome of each call. */
    private List<String> failRepeatedly(DefaultErrorHandler handler, Exception cause, int maxCalls) {
        List<String> outcomes = new ArrayList<>();
        for (int i = 0; i < maxCalls; i++) {
            consumerCalls.clear();
            try {
                handler.handleRemaining(new ListenerExecutionFailedException("wrapped", cause),
                        new ArrayList<>(List.of(record)), stubConsumer(), stubContainer());
                outcomes.add("returned" + consumerCalls);
            } catch (Exception e) {
                outcomes.add("threw:" + e.getClass().getSimpleName() + consumerCalls);
            }
        }
        return outcomes;
    }

    private boolean committed() {
        return consumerCalls.stream().anyMatch(c -> c.startsWith("commit:"));
    }

    // ─── back-off function ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * One isolated recovery observation: its own recorder, timers, stopper, controller and production-wired handler, so
     * an asynchronous callback of another case can never be mistaken for a recovery of this one.
     *
     * <p>Recovery is detected from signals that are set <em>synchronously on the thread that calls the handler</em>:
     * the controller's stop-request state (set before {@code stopAsync} returns), a pause recorded by the container
     * access (called inline by the recoverer), or a dead-letter. The container's actual {@code stop} runs later on the
     * stopper thread and is deliberately not used to detect recovery.
     */
    private final class RecoveryProbe implements AutoCloseable {

        final FakeContainer container = new FakeContainer();
        final List<ConsumerRecord<?, ?>> deadLettered = new CopyOnWriteArrayList<>();
        final WorkerContainerController controller;
        private final ScheduledExecutorService probeTimers = Executors.newSingleThreadScheduledExecutor();
        private final ScheduledExecutorService probeStopper = Executors.newSingleThreadScheduledExecutor();
        private final DefaultErrorHandler handler;

        RecoveryProbe() {
            controller = new WorkerContainerController(container, PROPS, () -> true, new SimpleMeterRegistry(),
                    Clock.systemUTC(), probeTimers, probeStopper, false);
            WorkerRecoverySupport.Recoverer recoverer = new WorkerRecoverySupport.Recoverer(classifier, controller,
                    (record, ex) -> deadLettered.add(record));
            handler = WorkerRecoverySupport.buildErrorHandler(recoverer, classifier, PROPS);
        }

        /**
         * Feeds the same failure to the handler until the recoverer has been initiated; returns the 1-based handler call
         * that initiated it, or -1. In spring-kafka an in-place retry is signalled by RecordInRetryException (after a
         * seek), exactly like a refused recovery, so the exception type cannot tell the two apart.
         *
         * @param afterEachCall invoked after every handler call, before the observation (lets a test interleave events)
         */
        int callWhenRecoveryRuns(Exception cause, int maxCalls, IntConsumer afterEachCall) {
            for (int call = 1; call <= maxCalls; call++) {
                try {
                    handler.handleRemaining(new ListenerExecutionFailedException("wrapped", cause),
                            new ArrayList<>(List.of(record)), stubConsumer(), stubContainer());
                } catch (Exception ignored) {
                    // RecordInRetryException: seek back (retry in place or refused recovery)
                }
                afterEachCall.accept(call);
                if (recoveryInitiated()) {
                    return call;
                }
            }
            return -1;
        }

        int callWhenRecoveryRuns(Exception cause, int maxCalls) {
            return callWhenRecoveryRuns(cause, maxCalls, call -> { });
        }

        boolean recoveryInitiated() {
            return controller.snapshot().stopped()
                    || container.calls.stream().anyMatch(c -> c.startsWith("pause:"))
                    || !deadLettered.isEmpty();
        }

        @Override
        public void close() {
            CountDownLatch gate = container.stopGate;
            if (gate != null) {
                gate.countDown();
            }
            probeTimers.shutdownNow();
            probeStopper.shutdownNow();
        }
    }

    @Test
    @DisplayName("K3 the back-off function retries in place exactly the categories that retry (3 times), the others recover at once")
    void backoffFollowsCategory() {
        for (Exception cause : List.of(
                new WorkerExceptions.Contention("c", null), new WorkerExceptions.Ordering("o", null),
                new WorkerExceptions.BrokerTransient("b", null), new WorkerExceptions.Unresolved("u", null))) {
            try (RecoveryProbe probe = new RecoveryProbe()) {
                assertThat(probe.callWhenRecoveryRuns(cause, 8)).as(cause.getClass().getSimpleName()).isEqualTo(4);
            }
        }
        for (Exception cause : List.of(
                new WorkerExceptions.Connectivity("c", null), new WorkerExceptions.PoisonMessageException("p", null),
                new WorkerExceptions.NonTransient("n", null), new IllegalStateException("unclassified"))) {
            try (RecoveryProbe probe = new RecoveryProbe()) {
                assertThat(probe.callWhenRecoveryRuns(cause, 8)).as(cause.getClass().getSimpleName()).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("K3 recovery is observed at the handler call that initiates it even while the asynchronous container stop is still pending")
    void recoveryObservationDoesNotWaitForTheAsynchronousStop() throws Exception {
        for (Exception cause : List.of(new WorkerExceptions.NonTransient("n", null), new IllegalStateException("unclassified"))) {
            try (RecoveryProbe probe = new RecoveryProbe()) {
                probe.container.stopGate = new CountDownLatch(1);   // the stopper thread is held inside stop()

                assertThat(probe.callWhenRecoveryRuns(cause, 8)).as(cause.getClass().getSimpleName()).isEqualTo(1);

                // The stop was requested synchronously ...
                assertThat(probe.controller.snapshot().stopped()).isTrue();
                // ... and is demonstrably still pending: the stopper thread is inside stop(), which has not recorded "stop".
                assertThat(probe.container.stopEntered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(probe.container.calls).doesNotContain("stop");
                assertThat(probe.container.stopThread).isNotEqualTo(Thread.currentThread().getName());

                // Opening the gate lets the real (asynchronous) stop complete.
                probe.container.stopGate.countDown();
                await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(probe.container.calls).contains("stop"));
            }
        }
    }

    @Test
    @DisplayName("K3 a delayed stop callback of an earlier case cannot be mistaken for the recovery of the current case")
    void staleStopCallbackOfEarlierCaseIsNotMistakenForRecovery() throws Exception {
        try (RecoveryProbe earlier = new RecoveryProbe(); RecoveryProbe current = new RecoveryProbe()) {
            earlier.container.stopGate = new CountDownLatch(1);
            assertThat(earlier.callWhenRecoveryRuns(new WorkerExceptions.NonTransient("n", null), 8)).isEqualTo(1);
            assertThat(earlier.container.stopEntered.await(2, TimeUnit.SECONDS)).isTrue();

            // The earlier case's stop completes while the current (retrying) case is between its first and second handler call.
            int recoveryCall = current.callWhenRecoveryRuns(new WorkerExceptions.Contention("c", null), 8, call -> {
                if (call == 1) {
                    earlier.container.stopGate.countDown();
                    await().atMost(Duration.ofSeconds(2))
                            .untilAsserted(() -> assertThat(earlier.container.calls).contains("stop"));
                }
            });

            assertThat(recoveryCall).as("the current case still retries in place 3 times before recovering").isEqualTo(4);
            assertThat(current.container.calls).as("the earlier case's stop never reached the current recorder").doesNotContain("stop");
            assertThat(current.controller.snapshot().stopped()).isFalse();
        }
    }

    // ─── recovery per category ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("K3 CONNECTIVITY: no in-place retry, partition paused until healthy, nothing committed, record re-seeked")
    void connectivity() {
        DefaultErrorHandler handler = handler();
        List<String> outcomes = failRepeatedly(handler, new WorkerExceptions.Connectivity("db down", new SQLException("x", "08006")), 1);

        assertThat(outcomes.get(0)).startsWith("threw:");
        assertThat(container.calls).containsExactly("pause:notify.dispatch.email.v1-2");
        assertThat(consumerCalls).anyMatch(c -> c.startsWith("seek:notify.dispatch.email.v1-2:17"));
        assertThat(committed()).isFalse();
        assertThat(deadLettered).isEmpty();
        assertThat(controller.pausedPartitions()).containsExactly(new TopicPartition("notify.dispatch.email.v1", 2));
    }

    @Test
    @DisplayName("K3 CONTENTION / ORDERING / BROKER_TRANSIENT: 3 in-place retries, then a timed pause; nothing committed; never dead-lettered")
    void retryingCategoriesPauseAfterExhaustion() {
        for (Exception cause : List.of(new WorkerExceptions.Contention("c", null),
                new WorkerExceptions.Ordering("o", null), new WorkerExceptions.BrokerTransient("b", null))) {
            container.calls.clear();
            DefaultErrorHandler handler = handler();
            List<String> outcomes = failRepeatedly(handler, cause, 4);

            assertThat(outcomes).as(cause.getClass().getSimpleName())
                    .allSatisfy(o -> assertThat(o).startsWith("threw:RecordInRetryException"));
            assertThat(committed()).isFalse();
            assertThat(container.calls).contains("pause:notify.dispatch.email.v1-2");
            assertThat(deadLettered).isEmpty();
            controller.onProgress(new TopicPartition("notify.dispatch.email.v1", 2));
        }
    }

    @Test
    @DisplayName("K3 UNRESOLVED: 3 in-place retries, then dead-letter; the recoverer returns normally so the offset is committed")
    void unresolvedIsDeadLetteredAndCommitted() {
        DefaultErrorHandler handler = handler();
        List<String> outcomes = failRepeatedly(handler, new WorkerExceptions.Unresolved("stuck", null), 4);

        assertThat(deadLettered).hasSize(1);
        assertThat(outcomes.get(3)).startsWith("returned").contains("commit:");
        assertThat(container.calls).isEmpty();
        assertThat(meters.counter("notify.worker.quarantined", "channel", "email", "reason", "UNRESOLVED_PROCESSING_STUCK").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("K3 POISON: dead-lettered immediately and committed")
    void poisonIsDeadLetteredImmediately() {
        DefaultErrorHandler handler = handler();
        List<String> outcomes = failRepeatedly(handler,
                new WorkerExceptions.PoisonMessageException("bad", null), 1);

        assertThat(deadLettered).hasSize(1);
        assertThat(outcomes.get(0)).contains("commit:");
        assertThat(meters.counter("notify.worker.quarantined", "channel", "email", "reason", "POISON_PAYLOAD").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("K4 a failed dead-letter send throws, so the offset is not committed and the record is re-seeked")
    void deadLetterSendFailureCommitsNothing() {
        deadLetterFails = true;
        DefaultErrorHandler handler = handler();
        List<String> outcomes = failRepeatedly(handler, new WorkerExceptions.PoisonMessageException("bad", null), 1);

        assertThat(outcomes.get(0)).startsWith("threw:");
        assertThat(committed()).isFalse();
        assertThat(consumerCalls).anyMatch(c -> c.startsWith("seek:notify.dispatch.email.v1-2:17"));
        assertThat(deadLettered).isEmpty();
        assertThat(meters.find("notify.worker.quarantined").counter()).as("a failed DLT send is not a quarantine").isNull();
    }

    @Test
    @DisplayName("K3 NON_TRANSIENT and UNCLASSIFIED: asynchronous stop, nothing committed, record re-seeked")
    void fatalCategoriesStop() {
        for (Exception cause : List.of(new WorkerExceptions.NonTransient("schema", null), new IllegalStateException("unknown"))) {
            container.calls.clear();
            controller = new WorkerContainerController(container, PROPS, () -> true, new SimpleMeterRegistry(),
                    Clock.systemUTC(), timers, stopper, false);
            DefaultErrorHandler handler = handler();
            List<String> outcomes = failRepeatedly(handler, cause, 1);

            assertThat(outcomes.get(0)).startsWith("threw:");
            assertThat(committed()).isFalse();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(container.calls).contains("stop"));
            assertThat(deadLettered).isEmpty();
            assertThat(controller.snapshot().stopped()).isTrue();
        }
    }

    @Test
    @DisplayName("K3 classification reaches through the container's wrapper exception")
    void classificationThroughWrapper() {
        assertThat(classifier.classify(new ListenerExecutionFailedException("w", new SQLException("x", "08006"))))
                .isEqualTo(ErrorCategory.CONNECTIVITY);
    }

    // ─── dead-letter record ────────────────────────────────────────────────────────────────────────────────────────

    /** Captures the producer record the dead-letter recoverer would send. */
    private static final class CapturingTemplate extends KafkaTemplate<String, byte[]> {
        final List<ProducerRecord<String, byte[]>> sent = new CopyOnWriteArrayList<>();
        volatile boolean fail;

        CapturingTemplate() {
            super(new DefaultKafkaProducerFactory<>(Map.of("bootstrap.servers", "localhost:1",
                    "key.serializer", org.apache.kafka.common.serialization.StringSerializer.class,
                    "value.serializer", org.apache.kafka.common.serialization.ByteArraySerializer.class)));
        }

        @Override
        public CompletableFuture<SendResult<String, byte[]>> send(ProducerRecord<String, byte[]> record) {
            sent.add(record);
            if (fail) {
                return CompletableFuture.failedFuture(new IllegalStateException("broker rejected"));
            }
            return CompletableFuture.completedFuture(new SendResult<>(record, null));
        }
    }

    @Test
    @DisplayName("K4 the dead-letter copy is byte-identical, keyed, headed with the reason, and carries no exception message, stack or body")
    void deadLetterRecordIsSanitized() {
        CapturingTemplate template = new CapturingTemplate();
        DeadLetterPublishingRecoverer dlpr = WorkerRecoverySupport.buildDeadLetterPublisher(template, PROPS, classifier);
        byte[] payload = "{\"k\":\"original-bytesé\"}".getBytes(StandardCharsets.UTF_8);
        ConsumerRecord<String, byte[]> original = new ConsumerRecord<>("notify.dispatch.email.v1", 4, 99L, "nid-1", payload);
        original.headers().add(new RecordHeader("traceparent", "00-aaaa-bbbb-01".getBytes(StandardCharsets.UTF_8)));
        original.headers().add(new RecordHeader("tenantId", "tenant-1".getBytes(StandardCharsets.UTF_8)));

        dlpr.accept(original, new WorkerExceptions.Poison("SECRET message text", new IllegalArgumentException("SECRET cause text")));

        assertThat(template.sent).hasSize(1);
        ProducerRecord<String, byte[]> sent = template.sent.get(0);
        assertThat(sent.topic()).isEqualTo("notify.dispatch.email.v1.DLT");
        assertThat(sent.partition()).as("producer chooses the partition by key").isNull();
        assertThat(sent.key()).isEqualTo("nid-1");
        assertThat(sent.value()).isEqualTo(payload);
        assertThat(header(sent, "x-notify-dlt-reason")).isEqualTo("POISON_PAYLOAD");
        assertThat(header(sent, "x-notify-error-category")).isEqualTo("POISON");
        assertThat(header(sent, "traceparent")).isEqualTo("00-aaaa-bbbb-01");
        assertThat(header(sent, "tenantId")).isEqualTo("tenant-1");
        for (Header h : sent.headers()) {
            String value = h.value() == null ? "" : new String(h.value(), StandardCharsets.UTF_8);
            assertThat(h.key() + "=" + value).doesNotContain("SECRET").doesNotContain("original-bytes");
        }
        assertThat(sent.headers().headers("kafka_dlt-exception-message")).isEmpty();
        assertThat(sent.headers().headers("kafka_dlt-exception-stacktrace")).isEmpty();
    }

    @Test
    @DisplayName("K4 an UNRESOLVED record is headed UNRESOLVED_PROCESSING_STUCK")
    void unresolvedReasonHeader() {
        CapturingTemplate template = new CapturingTemplate();
        DeadLetterPublishingRecoverer dlpr = WorkerRecoverySupport.buildDeadLetterPublisher(template, PROPS, classifier);
        dlpr.accept(new ConsumerRecord<>("notify.dispatch.email.v1", 0, 1L, "k", new byte[]{1}), new WorkerExceptions.Unresolved("u", null));
        assertThat(header(template.sent.get(0), "x-notify-dlt-reason")).isEqualTo("UNRESOLVED_PROCESSING_STUCK");
    }

    @Test
    @DisplayName("K4 a rejected dead-letter send makes the recoverer throw (so nothing is committed)")
    void rejectedSendThrows() {
        CapturingTemplate template = new CapturingTemplate();
        template.fail = true;
        DeadLetterPublishingRecoverer dlpr = WorkerRecoverySupport.buildDeadLetterPublisher(template, PROPS, classifier);
        assertThatThrownBy(() -> dlpr.accept(
                new ConsumerRecord<>("notify.dispatch.email.v1", 0, 1L, "k", new byte[]{1}), new WorkerExceptions.Poison("p", null)))
                .isInstanceOf(RuntimeException.class);
    }

    private static String header(ProducerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
