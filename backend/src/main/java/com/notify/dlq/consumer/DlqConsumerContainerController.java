package com.notify.dlq.consumer;

import com.notify.delivery.worker.classifier.ErrorCategory;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Container control for one dead-letter consumer (, decision ).
 *
 * <p><b>Why this exists instead of reusing {@code WorkerContainerController}.</b> That controller takes the
 * {@code workerPool} DataSource, and that bean is declared {@code @Conditional(OnAnyWorkerCondition.class)} - it
 * does not exist when every delivery worker is disabled, which is the default and the state these consumers must
 * work in. Reusing it would either fail context startup or force worker infrastructure into existence. What is
 * reused instead is the part that carries no worker dependency: the pure {@link ErrorCategory} classification.
 *
 * <p><b>What it does, and deliberately does not do.</b> It pauses a container for a bounded, escalating interval,
 * or stops it; it never commits an offset, never drops a record and never retries a provider call. A dead-letter
 * record that cannot be recorded durably must stay uncommitted, so every recovery path here leaves the offset
 * where it is.
 */
public class DlqConsumerContainerController implements org.springframework.beans.factory.DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DlqConsumerContainerController.class);

    private final KafkaListenerEndpointRegistry registry;
    private final String listenerId;
    private final Duration pauseInitial;
    private final Duration pauseMax;
    private final MeterRegistry meters;

    private final AtomicInteger consecutivePauses = new AtomicInteger();
    private final AtomicReference<String> lastStopReason = new AtomicReference<>();
    private final AtomicBoolean stopRequested = new AtomicBoolean();

    /**
     * A stop is dispatched here, never run on the caller thread. { #stop} is reached from the error handler,
     * which runs on the listener thread, and a synchronous { container.stop()} there would wait for the very
     * thread that is calling it. The delivery workers solved this the same way
     * ({ WorkerContainerController.stopAsync}).
     */
    private final ExecutorService stopper = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dlq-consumer-stop");
        thread.setDaemon(true);
        return thread;
    });

    public DlqConsumerContainerController(
            KafkaListenerEndpointRegistry registry,
            String listenerId,
            Duration pauseInitial,
            Duration pauseMax,
            MeterRegistry meters
    ) {
        this.registry = registry;
        this.listenerId = listenerId;
        this.pauseInitial = pauseInitial;
        this.pauseMax = pauseMax;
        this.meters = meters;
    }

    public String listenerId() {
        return listenerId;
    }

    /** The container, or null when the listener does not exist (both consumers disabled). */
    public MessageListenerContainer container() {
        return registry.getListenerContainer(listenerId);
    }

    public boolean isRunning() {
        MessageListenerContainer container = container();
        return container != null && container.isRunning();
    }

    public String lastStopReason() {
        return lastStopReason.get();
    }

    /**
     * Starts the listener. Called **only** after offset initialization has either completed successfully or been
     * determined unnecessary, which is what blocks startup until the position is resolved (, requirement 1).
     */
    public void start(String reason) {
        MessageListenerContainer container = container();
        if (container == null) {
            return;
        }
        if (container.isRunning()) {
            return;
        }
        // A deliberate restart may stop again later, so the one-shot stop guard is released here.
        stopRequested.set(false);
        log.info("Starting dead-letter listener {} ({})", listenerId, reason);
        container.start();
    }

    /** Pauses the partition for an escalating interval; the offset is retained. */
    public void pauseWithBackoff(TopicPartition partition, ErrorCategory category) {
        MessageListenerContainer container = container();
        if (container == null) {
            return;
        }
        int pauses = consecutivePauses.incrementAndGet();
        long millis = Math.min(pauseMax.toMillis(), pauseInitial.toMillis() * (long) Math.max(1, pauses));
        counter("paused", category.name());
        log.warn("Pausing dead-letter listener {} partition {} for {} ms after {} ({} consecutive)",
                listenerId, partition, millis, category, pauses);
        container.pausePartition(partition);
        container.getContainerProperties().setIdlePartitionEventInterval(millis);
    }

    /** Resumes a paused partition after a successful record; resets the escalation. */
    public void onSuccess(TopicPartition partition) {
        consecutivePauses.set(0);
        MessageListenerContainer container = container();
        if (container != null && container.isPartitionPaused(partition)) {
            container.resumePartition(partition);
        }
    }

    /**
     * Stops the listener. Used for a non-transient failure and for an unresolved offset position: a stopped
     * consumer is visible, and nothing is skipped or acknowledged (, requirements 1 and 2).
     */
    public void stop(String reason) {
        MessageListenerContainer container = container();
        if (container == null) {
            lastStopReason.set(reason);
            counter("stopped", reason);
            log.error("Dead-letter listener {} cannot be stopped because it does not exist ({})", listenerId, reason);
            return;
        }
        if (!stopRequested.compareAndSet(false, true)) {
            // Already stopping: the poll loop can raise the same unresolved position on every attempt, and one
            // stop is enough. The first reason is kept, because it is the one an operator must act on.
            return;
        }
        lastStopReason.set(reason);
        counter("stopped", reason);
        log.error("Stopping dead-letter listener {} (restart is manual): {}", listenerId, reason);
        stopper.execute(() -> {
            try {
                container.stop(() -> log.error("Dead-letter listener {} stopped: {}", listenerId, reason));
            } catch (RuntimeException e) {
                log.error("Dead-letter listener {} could not be stopped ({}): {}",
                        listenerId, reason, e.getClass().getName());
            }
        });
    }

    
    @Override
    public void destroy() {
        stopper.shutdownNow();
        try {
            stopper.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void counter(String event, String detail) {
        if (meters == null) {
            return;
        }
        meters.counter("notify.dlq.consumer." + event, "listener", listenerId, "detail", detail).increment();
    }
}
