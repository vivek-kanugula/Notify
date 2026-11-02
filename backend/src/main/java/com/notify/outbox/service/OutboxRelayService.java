package com.notify.outbox.service;

import com.notify.common.model.OutboxStatus;
import com.notify.kafka.producer.KafkaDispatchProducer;
import com.notify.outbox.entity.OutboxEventEntity;
import com.notify.outbox.repository.OutboxEventRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Transactional Outbox Relay service implementing specifications:
 * - Split-batch fresh / due-retry polling policy with maximum batch size 10 (or configurable <= 10).
 * - Row-level SELECT ... FOR UPDATE SKIP LOCKED locks held during bounded Kafka publish.
 * - Distinct ID enforcement preventing double-publishing within a single batch.
 * - Bounded lock/connection hold time: batch size B<=10, per-message timeout=1.0s, tx timeout=15s with 3.0s headroom.
 * - Atomic advancement of attempts and next_attempt_at on failure using OutboxBackoffCalculator.
 * - Post-batch completion time anchoring so failed rows remain ineligible for their full intended delay after commit.
 * - Immediate loop termination on interruption preserving untouched rows without artificial backoff.
 * - Actionable tiered logging preventing 500ms warning floods.
 * - Publishes canonical envelope to Kafka with traceparent and tenantId headers.
 * - Marks PUBLISHED upon broker acknowledgement.
 * - Preserves at-least-once crash recovery semantics.
 */
@Service
@ConditionalOnProperty(name = "notify.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);

    public static final long TRANSACTION_HEADROOM_MS = 3000L;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaDispatchProducer kafkaDispatchProducer;
    private Clock clock;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    // Strictly atomic ticket counter for B=1 alternation within this local relay instance
    private final AtomicLong pollTicket = new AtomicLong(0);

    @Value("${notify.outbox.batch-size:10}")
    private int batchSize = 10;

    @Value("${notify.outbox.per-message-timeout-ms:1000}")
    private long perMessageTimeoutMs = 1000L;

    /**
     * / (): how many full batches one scheduled tick may drain back-to-back before yielding to the
     * fixed delay. The 500 ms idle wait between batches, not the batch size, is what bounded relay throughput at
     * ~18-20 events/s; draining while a backlog exists removes that bound without touching the send budget.
     * Bounded so one tick can never monopolise the relay thread.
     */
    @Value("${notify.outbox.max-drain-cycles:20}")
    private int maxDrainCycles = 20;

    private int txTimeoutSeconds = 15;

    public OutboxRelayService(
            OutboxEventRepository outboxEventRepository,
            KafkaDispatchProducer kafkaDispatchProducer,
            Clock clock,
            org.springframework.transaction.PlatformTransactionManager transactionManager
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaDispatchProducer = kafkaDispatchProducer;
        this.clock = clock;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.transactionTemplate.setTimeout(txTimeoutSeconds);
    }

    @PostConstruct
    public void validateConfiguration() {
        validateTimeoutConfiguration(this.batchSize, this.perMessageTimeoutMs, this.txTimeoutSeconds);
    }

    /**
     * Validates that the configured batch size and per-message timeout leave safe transaction headroom.
     * Throws IllegalStateException if the configuration is unsafe.
     */
    public static void validateTimeoutConfiguration(int batchSize, long perMessageTimeoutMs, int txTimeoutSeconds) {
        if (perMessageTimeoutMs <= 0) {
            throw new IllegalStateException("Invalid outbox per-message-timeout-ms: must be > 0, got " + perMessageTimeoutMs);
        }
        int effectiveBatch = Math.min(Math.max(1, batchSize), 10);
        long maxSendBudget = (long) effectiveBatch * perMessageTimeoutMs;
        long maxAllowedBudget = (long) txTimeoutSeconds * 1000L - TRANSACTION_HEADROOM_MS;
        if (maxSendBudget > maxAllowedBudget) {
            throw new IllegalStateException(String.format(
                    "Unsafe outbox relay configuration: effective batch size %d with perMessageTimeoutMs %d ms " +
                    "yields max send budget of %d ms, which exceeds safe limit of %d ms for transaction timeout %d s " +
                    "(requiring %d ms database/commit headroom)",
                    effectiveBatch, perMessageTimeoutMs, maxSendBudget, maxAllowedBudget, txTimeoutSeconds, TRANSACTION_HEADROOM_MS
            ));
        }
    }

    /**
     * Scheduled relay tick. Drains consecutive full batches while a backlog remains (/ ).
     *
     * <p>Each cycle is still one independent {@link #processBatch()} transaction with its own
     * {@code FOR UPDATE SKIP LOCKED} fetch and its own send budget, so the per-batch invariants are
     * unchanged. The loop stops as soon as a cycle publishes fewer rows than a full batch - which covers both
     * "backlog drained" and "a publish failed" - so a failing broker is never hammered within one tick.
     *
     * @return total events published across the cycles of this tick
     */
    @Scheduled(fixedDelayString = "${notify.outbox.relay-interval-ms:500}")
    public int relayPendingEvents() {
        int fullBatch = effectiveBatchSize();
        int cycles = Math.max(1, this.maxDrainCycles);
        int total = 0;
        for (int cycle = 0; cycle < cycles; cycle++) {
            int published = processBatch();
            total += published;
            if (published < fullBatch) {
                break;
            }
        }
        return total;
    }

    /** The batch size actually used, after the safety cap. */
    private int effectiveBatchSize() {
        return Math.min(Math.max(1, this.batchSize), 10);
    }

    /**
     * Executes a single batch poll-and-publish cycle within a bounded transaction.
     * The row-level FOR UPDATE SKIP LOCKED lock is held during the bounded Kafka publish,
     * ensuring concurrent relays never process the same row simultaneously.
     *
     * @return number of events published in this batch
     */
    public int processBatch() {
        Integer result = transactionTemplate.execute(status -> {
            int effectiveBatch = effectiveBatchSize();
            Instant now = Instant.now(clock);

            List<OutboxEventEntity> batch = fetchBatch(effectiveBatch, now);
            if (batch.isEmpty()) {
                return 0;
            }

            // Assert that one batch contains distinct IDs and never publishes an event twice
            Set<UUID> seenIds = new HashSet<>();
            for (OutboxEventEntity event : batch) {
                if (!seenIds.add(event.getId())) {
                    throw new IllegalStateException("Duplicate outbox event ID detected in single batch: " + event.getId());
                }
            }

            int publishedCount = 0;
            long deadlineMillis = System.currentTimeMillis() + ((long) txTimeoutSeconds * 1000L - TRANSACTION_HEADROOM_MS);

            List<FailedEvent> failedEvents = new ArrayList<>();

            for (OutboxEventEntity event : batch) {
                if (System.currentTimeMillis() >= deadlineMillis) {
                    log.warn("Outbox relay batch approaching transaction timeout deadline; yielding batch early");
                    break;
                }

                try {
                    long startSend = System.currentTimeMillis();
                    CompletableFuture<SendResult<String, String>> future = kafkaDispatchProducer.send(
                            event.getTopic(),
                            event.getMessageKey(),
                            event.getPayload(),
                            event.getTraceContext(),
                            event.getTenantId()
                    );
                    long elapsedSend = System.currentTimeMillis() - startSend;
                    long remainingWait = Math.max(0, perMessageTimeoutMs - elapsedSend);
                    future.get(remainingWait, TimeUnit.MILLISECONDS);

                    // Update within the same transaction holding the row lock
                    outboxEventRepository.updateStatusAndPublishedAt(event.getId(), OutboxStatus.PUBLISHED, Instant.now(clock));
                    publishedCount++;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Outbox relay interrupted while publishing event {}; halting batch processing immediately", event.getId());
                    failedEvents.add(new FailedEvent(event, ie));
                    break; // Halts processing immediately so remaining locked rows remain untouched
                } catch (Exception ex) {
                    Throwable cause = ex;
                    if (ex instanceof ExecutionException && ex.getCause() != null) {
                        cause = ex.getCause();
                    }
                    if (cause instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        log.warn("Outbox relay interrupted while waiting on future for event {}; halting batch processing immediately", event.getId());
                        failedEvents.add(new FailedEvent(event, cause));
                        break;
                    }
                    failedEvents.add(new FailedEvent(event, cause));
                }
            }

            // Anchors retry backoff timestamps to batch completion time right before commit
            // so failed rows remain ineligible for their full intended delay after the batch commits
            if (!failedEvents.isEmpty()) {
                Instant batchCompletionTime = Instant.now(clock);
                for (FailedEvent failure : failedEvents) {
                    recordFailureAndLog(failure.event(), batchCompletionTime, failure.cause());
                }
            }

            return publishedCount;
        });
        return result != null ? result : 0;
    }

    private List<OutboxEventEntity> fetchBatch(int effectiveBatch, Instant now) {
        List<OutboxEventEntity> batch = new ArrayList<>(effectiveBatch);

        if (effectiveBatch == 1) {
            boolean preferFresh = nextPreferenceForBatchSizeOne();
            if (preferFresh) {
                List<OutboxEventEntity> fresh = outboxEventRepository.findPendingFreshForUpdateSkipLocked(1);
                if (!fresh.isEmpty()) {
                    batch.addAll(fresh);
                } else {
                    // Work-stealing: poll retry queue if fresh is empty
                    batch.addAll(outboxEventRepository.findPendingRetriesForUpdateSkipLocked(now, 1));
                }
            } else {
                List<OutboxEventEntity> retries = outboxEventRepository.findPendingRetriesForUpdateSkipLocked(now, 1);
                if (!retries.isEmpty()) {
                    batch.addAll(retries);
                } else {
                    // Work-stealing: poll fresh queue if retries is empty
                    batch.addAll(outboxEventRepository.findPendingFreshForUpdateSkipLocked(1));
                }
            }
            return batch;
        }

        // effectiveBatch in [2, 10]: reserved capacity for both classes when both are eligible
        int freshQuota = Math.max(1, Math.min(effectiveBatch - 1, (int) Math.round(effectiveBatch * 0.7)));

        // 1. Fetch fresh quota
        List<OutboxEventEntity> fresh = outboxEventRepository.findPendingFreshForUpdateSkipLocked(freshQuota);
        batch.addAll(fresh);

        // 2. Fetch retries up to remaining capacity (reserved retry quota + work-stealing from unused fresh quota)
        int remainingForRetry = effectiveBatch - batch.size();
        if (remainingForRetry > 0) {
            List<OutboxEventEntity> retries = outboxEventRepository.findPendingRetriesForUpdateSkipLocked(now, remainingForRetry);
            batch.addAll(retries);
        }

        // 3. Top-up fresh if retry queue didn't fill the remainder and batch still has capacity
        int remainingForTopUp = effectiveBatch - batch.size();
        if (remainingForTopUp > 0) {
            List<UUID> selectedFreshIds = fresh.stream().map(OutboxEventEntity::getId).toList();
            List<OutboxEventEntity> topUpFresh;
            if (selectedFreshIds.isEmpty()) {
                topUpFresh = outboxEventRepository.findPendingFreshForUpdateSkipLocked(remainingForTopUp);
            } else {
                topUpFresh = outboxEventRepository.findPendingFreshExcludingIdsForUpdateSkipLocked(selectedFreshIds, remainingForTopUp);
            }
            batch.addAll(topUpFresh);
        }

        return batch;
    }

    /**
     * Strictly atomic ticket for B=1 alternation within this local relay instance.
     * Note: alternation is local to this relay instance and does not guarantee global cross-JVM interleaving.
     */
    public boolean nextPreferenceForBatchSizeOne() {
        return (pollTicket.getAndIncrement() & 1L) == 0L;
    }

    private void recordFailureAndLog(OutboxEventEntity event, Instant baseTime, Throwable cause) {
        int nextAttempts = event.getAttempts() + 1;
        Instant nextAttemptAt = OutboxBackoffCalculator.calculateNextAttemptAt(nextAttempts, baseTime);
        outboxEventRepository.recordFailure(event.getId(), nextAttemptAt);

        String errorMsg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        if (nextAttempts == 1) {
            log.info("Kafka publish failed for outbox event {} to topic {} on initial attempt; scheduled for backoff retry at {}: {}",
                    event.getId(), event.getTopic(), nextAttemptAt, errorMsg);
        } else if (nextAttempts == 5) {
            log.warn("Kafka publish failed 5 attempts for outbox event {} to topic {}; escalating backoff to {}: {}",
                    event.getId(), event.getTopic(), nextAttemptAt, errorMsg);
        } else if (nextAttempts > 5 && nextAttempts % 20 == 0) {
            log.warn("Kafka publish repeatedly failing (attempt {}) for outbox event {} to topic {}; next attempt at {}: {}",
                    nextAttempts, event.getId(), event.getTopic(), nextAttemptAt, errorMsg);
        } else {
            log.debug("Kafka publish failed attempt {} for outbox event {} to topic {}; backoff retry at {}: {}",
                    nextAttempts, event.getId(), event.getTopic(), nextAttemptAt, errorMsg);
        }
    }

    /**
     * Purges PUBLISHED outbox events older than 7 days per TRD §8.
     */
    @Scheduled(cron = "${notify.outbox.cleanup-cron:0 0 3 * * *}")
    @Transactional
    public int purgeOldPublishedEvents() {
        Instant cutoff = Instant.now(clock).minus(7, ChronoUnit.DAYS);
        int deleted = outboxEventRepository.purgePublishedBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} PUBLISHED outbox events older than {}", deleted, cutoff);
        }
        return deleted;
    }

    private record FailedEvent(OutboxEventEntity event, Throwable cause) {}

    // Exposed for testing
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public void setPerMessageTimeoutMs(long perMessageTimeoutMs) {
        this.perMessageTimeoutMs = perMessageTimeoutMs;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public long getPerMessageTimeoutMs() {
        return perMessageTimeoutMs;
    }
}
