package com.notify.delivery.worker.control;

import com.notify.delivery.worker.config.WorkerTuning;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Pause, escalation and stop control of one channel's listener container (plan section 8.4; per channel since ).
 *
 * <ul>
 *   <li>{@link #pauseUntilHealthy}: database connectivity lost. Pauses the partition (offset retained) until a probe
 *       ({@code SELECT 1} on the worker pool) succeeds {@code healthSuccesses} times in a row.</li>
 *   <li>{@link #pauseWithBackoff}: exhausted contention/ordering/broker-transient retries. Pauses the partition with an
 *       escalating delay (30 s, 60 s, 120 s, capped 300 s) per consecutive pause; a successful acknowledgment on that
 *       partition resets the counter. At the {@code escalateAfter}-th consecutive pause: ERROR log, metric
 *       {@code notify.worker.escalations}, and the health indicator reports OUT_OF_SERVICE. Offsets stay retained, so
 *       no record is ever skipped.</li>
 *   <li>{@link #stopAsync}: fatal schema/config/unclassified failure. The container is stopped from a dedicated thread
 *       (a synchronous stop from the listener thread would wait for itself). Restart is manual.</li>
 * </ul>
 * Also the container's rebalance listener: timers of revoked partitions are cancelled and stale pause requests cleared
 * so a pause can never outlive partition ownership.
 */
public class WorkerContainerController implements ConsumerRebalanceListener, DisposableBean {

    /** Listener id of the email worker container (). */
    public static final String LISTENER_ID = "notify-email-delivery-worker";
    /** Listener id of the webhook worker container (). */
    public static final String WEBHOOK_LISTENER_ID = "notify-webhook-delivery-worker";
    public static final String INAPP_LISTENER_ID = "notify-inapp-delivery-worker";

    private static final Logger log = LoggerFactory.getLogger(WorkerContainerController.class);
    private static final String LAG_METRIC = "records-lag-max";

    /** Narrow view of the container so the controller can be unit-tested without Kafka. */
    public interface ContainerAccess {
        void pause(TopicPartition partition);

        void resume(TopicPartition partition);

        void stop(Runnable onStopped);

        boolean isRunning();

        Collection<TopicPartition> assignedPartitions();

        /** Highest consumer lag in records over the listener threads, or NaN when unknown. */
        double maxLag();
    }

    enum PauseReason { CONNECTIVITY, BACKOFF }

    private static final class PauseState {
        PauseReason reason;
        int consecutive;
        boolean escalated;
        Instant pausedAt;
        ScheduledFuture<?> resumeTask;
    }

    /** Immutable state snapshot for the health indicator. */
    public record Snapshot(
            boolean containerRunning,
            boolean stopped,
            String stopReason,
            int assignedPartitions,
            List<String> pausedPartitions,
            List<String> escalatedPartitions,
            double maxLag,
            Instant startedAt,
            boolean databaseHealthy
    ) {
    }

    private final ContainerAccess container;
    private final WorkerTuning props;
    private final BooleanSupplier databaseProbe;
    private final MeterRegistry meters;
    private final Clock clock;
    private final ScheduledExecutorService timers;
    private final ScheduledExecutorService stopper;
    private final boolean ownsExecutors;

    private final Map<TopicPartition, PauseState> pauses = new ConcurrentHashMap<>();
    private ScheduledFuture<?> healthPoll;
    private int consecutiveHealthy;
    private volatile boolean databaseHealthy = true;
    private volatile String stopReason;
    private volatile boolean stopRequested;
    private final Instant startedAt;

    /**
     * Production wiring, one instance per enabled channel worker: each gets its own listener container, pause and
     * escalation state, and stop state, so one channel cannot pause or stop another ().
     */
    public WorkerContainerController(
            KafkaListenerEndpointRegistry registry,
            String listenerId,
            WorkerTuning props,
            DataSource workerPool,
            ObjectProvider<MeterRegistry> meters,
            Clock clock
    ) {
        this(new RegistryContainerAccess(registry, listenerId), props, workerProbe(workerPool),
                meters.getIfAvailable(SimpleMeterRegistry::new), clock,
                Executors.newSingleThreadScheduledExecutor(daemon(props.name() + "-worker-control")),
                Executors.newSingleThreadScheduledExecutor(daemon(props.name() + "-worker-stop")), true);
    }

    /** For tests and wiring: every collaborator explicit. */
    public WorkerContainerController(
            ContainerAccess container,
            WorkerTuning props,
            BooleanSupplier databaseProbe,
            MeterRegistry meters,
            Clock clock,
            ScheduledExecutorService timers,
            ScheduledExecutorService stopper,
            boolean ownsExecutors
    ) {
        this.container = container;
        this.props = props;
        this.databaseProbe = databaseProbe;
        this.meters = meters;
        this.clock = clock;
        this.timers = timers;
        this.stopper = stopper;
        this.ownsExecutors = ownsExecutors;
        this.startedAt = clock.instant();
    }

    // ─── pause / resume ────────────────────────────────────────────────────────────────────────────────────────────

    /** Connectivity pause: no timer; a health poll resumes the partition. */
    public synchronized void pauseUntilHealthy(TopicPartition partition) {
        PauseState state = pauses.computeIfAbsent(partition, p -> new PauseState());
        cancel(state);
        state.reason = PauseReason.CONNECTIVITY;
        state.consecutive++;
        state.pausedAt = clock.instant();
        container.pause(partition);
        meters.counter("notify.worker.pauses", "channel", props.name(), "reason", "connectivity").increment();
        log.warn("{} worker paused partition {} until the database answers again (offset retained)", props.name(), partition);
        startHealthPoll();
    }

    /** Timed pause with escalating delay; the offset stays retained. */
    public synchronized void pauseWithBackoff(TopicPartition partition) {
        PauseState state = pauses.computeIfAbsent(partition, p -> new PauseState());
        cancel(state);
        state.reason = PauseReason.BACKOFF;
        state.consecutive++;
        state.pausedAt = clock.instant();

        Duration delay = backoffDelay(state.consecutive);
        container.pause(partition);
        state.resumeTask = timers.schedule(() -> resumeAfterBackoff(partition), delay.toMillis(), TimeUnit.MILLISECONDS);
        meters.counter("notify.worker.pauses", "channel", props.name(), "reason", "backoff").increment();
        log.warn("{} worker paused partition {} for {} (consecutive pause {}; offset retained)", props.name(),
                partition, delay, state.consecutive);

        if (state.consecutive >= props.escalateAfter() && !state.escalated) {
            state.escalated = true;
            meters.counter("notify.worker.escalations", "channel", props.name()).increment();
            log.error("ESCALATION: {} worker partition {} paused {} times in a row without progress; "
                    + "records are retained, not skipped. Investigate database contention or ordering.",
                    partition, state.consecutive);
        }
    }

    /** {@code initial * 2^(n-1)} capped at {@code pauseMax}. */
    Duration backoffDelay(int consecutive) {
        long initial = props.pauseInitial().toMillis();
        long max = props.pauseMax().toMillis();
        long millis = initial;
        for (int i = 1; i < consecutive && millis < max; i++) {
            millis = Math.min(max, millis * 2);
        }
        return Duration.ofMillis(Math.min(millis, max));
    }

    private void resumeAfterBackoff(TopicPartition partition) {
        synchronized (this) {
            PauseState state = pauses.get(partition);
            if (state == null || state.reason != PauseReason.BACKOFF) {
                return;
            }
            state.resumeTask = null;
            state.pausedAt = null;
        }
        container.resume(partition);
        log.info("{} worker resumed partition {} after backoff", props.name(), partition);
    }

    /** A record was dead-lettered (the DLT send succeeded) with the given header reason. */
    public void onQuarantined(String reason) {
        meters.counter("notify.worker.quarantined", "channel", props.name(), "reason", reason).increment();
    }

    /** A record on this partition completed and was acknowledged: the partition is making progress again. */
    public synchronized void onProgress(TopicPartition partition) {
        PauseState state = pauses.get(partition);
        if (state != null && state.pausedAt == null) {
            pauses.remove(partition);
        }
    }

    // ─── connectivity health poll ──────────────────────────────────────────────────────────────────────────────────

    private void startHealthPoll() {
        if (healthPoll != null && !healthPoll.isDone()) {
            return;
        }
        consecutiveHealthy = 0;
        long interval = props.healthPollInterval().toMillis();
        healthPoll = timers.scheduleWithFixedDelay(this::pollDatabase, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void pollDatabase() {
        boolean ok;
        try {
            ok = databaseProbe.getAsBoolean();
        } catch (RuntimeException e) {
            ok = false;
        }
        List<TopicPartition> toResume = new ArrayList<>();
        synchronized (this) {
            databaseHealthy = ok;
            consecutiveHealthy = ok ? consecutiveHealthy + 1 : 0;
            if (consecutiveHealthy >= props.healthSuccesses()) {
                pauses.forEach((tp, state) -> {
                    if (state.reason == PauseReason.CONNECTIVITY && state.pausedAt != null) {
                        state.pausedAt = null;
                        toResume.add(tp);
                    }
                });
                if (healthPoll != null) {
                    healthPoll.cancel(false);
                    healthPoll = null;
                }
            }
        }
        for (TopicPartition tp : toResume) {
            container.resume(tp);
            log.info("{} worker resumed partition {} after the database recovered", props.name(), tp);
        }
    }

    // ─── stop ──────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Stops the container from a dedicated thread; the listener thread never waits. Idempotent. */
    public void stopAsync(String reason) {
        synchronized (this) {
            if (stopRequested) {
                return;
            }
            stopRequested = true;
            stopReason = reason;
        }
        meters.counter("notify.worker.stops", "channel", props.name()).increment();
        log.error("{} worker container stopping (restart is manual): {}", props.name(), reason);
        stopper.execute(() -> {
            try {
                container.stop(() -> log.error("{} worker container stopped: {}", props.name(), reason));
            } catch (RuntimeException e) {
                log.error("Failed to stop the {} worker container: {}", props.name(), e.getClass().getName());
            }
        });
    }

    // ─── rebalance ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        clearOwnership(partitions);
    }

    @Override
    public void onPartitionsLost(Collection<TopicPartition> partitions) {
        clearOwnership(partitions);
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        // Any partition we did not pause ourselves must not carry a stale pause request from an earlier ownership.
        List<TopicPartition> untouched = new ArrayList<>();
        synchronized (this) {
            for (TopicPartition tp : partitions) {
                if (!pauses.containsKey(tp)) {
                    untouched.add(tp);
                }
            }
        }
        if (!untouched.isEmpty()) {
            // Runs after the assignment is applied, on the control thread (never on the consumer thread).
            timers.schedule(() -> untouched.forEach(this::safeResume), 0, TimeUnit.MILLISECONDS);
        }
    }

    private void clearOwnership(Collection<TopicPartition> partitions) {
        List<TopicPartition> cleared = new ArrayList<>();
        synchronized (this) {
            for (TopicPartition tp : partitions) {
                PauseState state = pauses.remove(tp);
                if (state != null) {
                    cancel(state);
                    cleared.add(tp);
                }
            }
        }
        cleared.forEach(this::safeResume);
    }

    private void safeResume(TopicPartition tp) {
        try {
            container.resume(tp);
        } catch (RuntimeException e) {
            log.debug("Resume of {} ignored: {}", tp, e.getClass().getName());
        }
    }

    private static void cancel(PauseState state) {
        if (state.resumeTask != null) {
            state.resumeTask.cancel(false);
            state.resumeTask = null;
        }
    }

    // ─── introspection ─────────────────────────────────────────────────────────────────────────────────────────────

    public synchronized Snapshot snapshot() {
        List<String> paused = new ArrayList<>();
        List<String> escalated = new ArrayList<>();
        pauses.forEach((tp, state) -> {
            if (state.pausedAt != null) {
                paused.add(tp.toString());
            }
            if (state.escalated) {
                escalated.add(tp.toString());
            }
        });
        return new Snapshot(container.isRunning(), stopRequested, stopReason,
                container.assignedPartitions().size(), List.copyOf(paused), List.copyOf(escalated),
                container.maxLag(), startedAt, databaseHealthy);
    }

    public Set<TopicPartition> pausedPartitions() {
        Set<TopicPartition> out = new java.util.HashSet<>();
        pauses.forEach((tp, state) -> {
            if (state.pausedAt != null) {
                out.add(tp);
            }
        });
        return out;
    }

    @Override
    public void destroy() {
        if (ownsExecutors) {
            timers.shutdownNow();
            stopper.shutdownNow();
        }
    }

    // ─── production wiring ─────────────────────────────────────────────────────────────────────────────────────────

    private static BooleanSupplier workerProbe(DataSource workerPool) {
        // A JdbcTemplate bound directly to the worker pool (never through the router), 2 s statement bound.
        JdbcTemplate jdbc = new JdbcTemplate(workerPool);
        jdbc.setQueryTimeout(2);
        return () -> {
            try {
                Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
                return one != null && one == 1;
            } catch (RuntimeException e) {
                return false;
            }
        };
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread t = new Thread(runnable, name);
            t.setDaemon(true);
            return t;
        };
    }

    /** Container access backed by the listener endpoint registry (looked up lazily). */
    static final class RegistryContainerAccess implements ContainerAccess {
        private final KafkaListenerEndpointRegistry registry;
        private final String listenerId;

        RegistryContainerAccess(KafkaListenerEndpointRegistry registry, String listenerId) {
            this.registry = registry;
            this.listenerId = listenerId;
        }

        private ConcurrentMessageListenerContainer<?, ?> container() {
            MessageListenerContainer c = registry.getListenerContainer(listenerId);
            if (c instanceof ConcurrentMessageListenerContainer<?, ?> concurrent) {
                return concurrent;
            }
            throw new IllegalStateException("Listener container '" + listenerId + "' is not available");
        }

        @Override
        public void pause(TopicPartition partition) {
            container().pausePartition(partition);
        }

        @Override
        public void resume(TopicPartition partition) {
            container().resumePartition(partition);
        }

        @Override
        public void stop(Runnable onStopped) {
            container().stop(onStopped);
        }

        @Override
        public boolean isRunning() {
            MessageListenerContainer c = registry.getListenerContainer(listenerId);
            return c != null && c.isRunning();
        }

        @Override
        public Collection<TopicPartition> assignedPartitions() {
            MessageListenerContainer c = registry.getListenerContainer(listenerId);
            Collection<TopicPartition> assigned = c == null ? null : c.getAssignedPartitions();
            return assigned == null ? List.of() : assigned;
        }

        @Override
        public double maxLag() {
            MessageListenerContainer c = registry.getListenerContainer(listenerId);
            if (!(c instanceof ConcurrentMessageListenerContainer<?, ?> concurrent)) {
                return Double.NaN;
            }
            double max = Double.NaN;
            for (Map<MetricName, ? extends Metric> metrics : concurrent.metrics().values()) {
                for (Map.Entry<MetricName, ? extends Metric> entry : metrics.entrySet()) {
                    if (LAG_METRIC.equals(entry.getKey().name())
                            && entry.getValue().metricValue() instanceof Number value
                            && !Double.isNaN(value.doubleValue())) {
                        max = Double.isNaN(max) ? value.doubleValue() : Math.max(max, value.doubleValue());
                    }
                }
            }
            return max;
        }
    }
}
