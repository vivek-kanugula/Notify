package com.notify.delivery.worker;

import com.notify.common.db.WorkerPoolScope;
import com.notify.delivery.model.ClaimDisposition;
import com.notify.delivery.model.FencedOutException;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.delivery.model.ReconciliationOutcome;
import com.notify.delivery.service.ClaimDispositionClassifier;
import com.notify.delivery.service.DeliveryOrchestrator;
import com.notify.delivery.service.DeliveryResultService;
import com.notify.delivery.service.ReconciliationEvaluator;
import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.delivery.worker.classifier.ErrorCategory;
import com.notify.delivery.worker.control.WorkerContainerController;
import com.notify.delivery.worker.exception.WorkerExceptions;
import com.notify.delivery.worker.model.DispatchEnvelope;
import com.notify.notification.dto.ClaimResult;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.notification.dto.NotificationEvidence;
import com.notify.notification.model.NotificationTransitioner;
import com.notify.notification.service.NotificationService;
import com.notify.quartz.service.NotificationSchedulerService;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The delivery flow every channel worker shares (plan sections 6 and 7, generalized in ).
 *
 * <p>Flow per record: parse (poison on malformed) → enter the worker pool scope → claim (own short transaction) →
 * on a claim: tenant gate and provider call outside any transaction, then one atomic result transaction, then
 * post-commit steps (Quartz retry trigger, acknowledgment); on no claim: classify the record against a single-row
 * evidence read.
 *
 * <p><b>Acknowledgment ownership</b> (exactly one owner per path): a worker acknowledges only when the outcome is
 * durable or the record is provably obsolete; every failure path throws a typed exception and the error handler decides
 * (retry, pause, dead-letter after a successful DLT send, or stop). A worker never acknowledges in an error path.
 *
 * <p>This class exists so acknowledgment ownership, the reconciliation rules and the disposition table cannot drift
 * between channels. Channel-specific parts are the two abstract methods: parsing its own envelope and the default
 * sender its claim stamps (for email; none for any other channel).
 *
 * <p><b>Crash window (decision 1, dev/demo first):</b> a PROCESSING row whose owner died is not reclaimed in /; a
 * redelivered record meeting it is quarantined after bounded retries. Automatic recovery is /.
 */
public abstract class AbstractDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(AbstractDeliveryWorker.class);

    protected final NotificationTransitioner transitioner;
    protected final NotificationService notificationService;
    protected final DeliveryOrchestrator orchestrator;
    protected final DeliveryResultService resultService;
    protected final ClaimDispositionClassifier dispositionClassifier;
    protected final ReconciliationEvaluator reconciliationEvaluator;
    protected final DeliveryExceptionClassifier classifier;
    protected final NotificationSchedulerService scheduler;
    protected final WorkerContainerController controller;
    protected final Clock clock;
    protected final MeterRegistry meters;
    private final long[] persistRetryDelaysMs;

    protected AbstractDeliveryWorker(
            NotificationTransitioner transitioner,
            NotificationService notificationService,
            DeliveryOrchestrator orchestrator,
            DeliveryResultService resultService,
            ClaimDispositionClassifier dispositionClassifier,
            ReconciliationEvaluator reconciliationEvaluator,
            DeliveryExceptionClassifier classifier,
            NotificationSchedulerService scheduler,
            WorkerContainerController controller,
            Clock clock,
            MeterRegistry meters,
            long[] persistRetryDelaysMs
    ) {
        this.transitioner = transitioner;
        this.notificationService = notificationService;
        this.orchestrator = orchestrator;
        this.resultService = resultService;
        this.dispositionClassifier = dispositionClassifier;
        this.reconciliationEvaluator = reconciliationEvaluator;
        this.classifier = classifier;
        this.scheduler = scheduler;
        this.controller = controller;
        this.clock = clock;
        this.meters = meters;
        this.persistRetryDelaysMs = persistRetryDelaysMs.clone();
    }

    // ─── channel-specific ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Lower-case channel name used as the bounded {@code channel} tag on this worker's metrics (F5). Bounded by
     * construction: one value per channel worker, never request- or tenant-derived.
     */
    protected abstract String channelName();

    /** Parses and validates this channel's dispatch envelope; any violation must be a poison message. */
    protected abstract DispatchEnvelope parseEvent(byte[] payload);

    /**
     * Value written into {@code notifications.sender} when it is still null at the first claim. Email supplies the
     * configured sender (); other channels supply {@code null}, so the column stays null for them.
     */
    protected abstract String defaultSender();

    // ─── main flow ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** Entry point for a listener. Every escaping failure is normalized so the retry budget is category-stable. */
    protected final void handleRecord(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        try {
            process(record, acknowledgment);
        } catch (Exception e) {
            // Includes checked exceptions (for example SQLException) that proxies may rethrow without wrapping.
            throw classifier.normalize(e);
        }
    }

    private void process(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        DispatchEnvelope event = parseEvent(record.value());

        try (WorkerPoolScope ignored = WorkerPoolScope.enter()) {
            ClaimResult claimResult = transitioner.claim(
                    event.tenantId(), event.notificationId(), event.generation(), defaultSender());
            if (claimResult instanceof ClaimResult.Claimed claimed) {
                handleClaimed(record, claimed.snapshot(), acknowledgment);
            } else {
                handleUnclaimed(record, event, acknowledgment);
            }
        }
    }

    // ─── claimed: deliver, persist, reconcile, post-commit, acknowledge ────────────────────────────────────────────

    private void handleClaimed(ConsumerRecord<String, byte[]> record, ClaimSnapshot claim, Acknowledgment acknowledgment) {
        PersistableOutcome outcome = orchestrator.deliver(claim);

        Persistence persistence = persistWithReconcile(claim, outcome);
        switch (persistence) {
            case PERSISTED, RECONCILED_COMMITTED -> {
                countOutcome(outcome, persistence == Persistence.RECONCILED_COMMITTED);
                postCommit(claim, outcome, traceparent(record));
                acknowledge(record, acknowledgment);
            }
            case DISCARDED -> {
                // Absent or superseded: this claim's result is not (and will never be) durable. Nothing to repair.
                meters.counter("notify.delivery.results", "channel", channelName(), "outcome", "superseded").increment();
                acknowledge(record, acknowledgment);
            }
        }
    }

    private enum Persistence { PERSISTED, RECONCILED_COMMITTED, DISCARDED }

    /**
     * The provider result is held in memory and the provider is never called again for this claim (6.5). Up to four
     * persist attempts, only for CONNECTIVITY/CONTENTION failures; each retry begins with the fenced conditional update,
     * so if an earlier attempt had actually committed the retry updates zero rows and reconciles exactly once.
     */
    private Persistence persistWithReconcile(ClaimSnapshot claim, PersistableOutcome outcome) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt <= persistRetryDelaysMs.length; attempt++) {
            try {
                resultService.persist(claim, outcome);
                return Persistence.PERSISTED;
            } catch (FencedOutException fenced) {
                return reconcile(claim, null);
            } catch (RuntimeException e) {
                ErrorCategory category = classifier.classify(e);
                if (category != ErrorCategory.CONNECTIVITY && category != ErrorCategory.CONTENTION) {
                    throw e;
                }
                lastFailure = e;
                log.warn("Persisting the delivery result failed ({}); attempt {} of {} (notificationId={}, claimSeq={})",
                        category, attempt + 1, persistRetryDelaysMs.length + 1, claim.id(), claim.claimSeq());
                if (attempt < persistRetryDelaysMs.length) {
                    sleep(persistRetryDelaysMs[attempt]);
                }
            }
        }
        // All attempts failed: one final evidence read decides whether an earlier attempt actually committed.
        return reconcile(claim, lastFailure);
    }

    private Persistence reconcile(ClaimSnapshot claim, RuntimeException persistFailure) {
        Optional<NotificationEvidence> evidence = notificationService.getClaimEvidence(claim.tenantId(), claim.id());
        ReconciliationOutcome outcome = reconciliationEvaluator.evaluate(claim, evidence);
        return switch (outcome) {
            case COMMITTED -> Persistence.RECONCILED_COMMITTED;
            case ABSENT, SUPERSEDED -> {
                log.warn("Delivery result discarded: claim {} (generation {}, claimSeq {}) was {} (notificationId={})",
                        claim.id(), claim.generation(), claim.claimSeq(), outcome, claim.id());
                yield Persistence.DISCARDED;
            }
            case UNRESOLVED -> throw new WorkerExceptions.Unresolved(
                    "Result not visible after persistence failures (claimSeq " + claim.claimSeq() + ")", persistFailure);
        };
    }

    // ─── not claimed: single-row evidence and the disposition table (6.2) ───────────────────────────────────────────

    private void handleUnclaimed(ConsumerRecord<String, byte[]> record, DispatchEnvelope event, Acknowledgment acknowledgment) {
        Optional<NotificationEvidence> evidence = notificationService.getClaimEvidence(event.tenantId(), event.notificationId());
        ClaimDisposition disposition = dispositionClassifier.classify(evidence, event.generation(), clock.instant());

        switch (disposition) {
            case ROW_ABSENT -> {
                log.warn("Dispatch record for unknown notification discarded (notificationId={}, tenantId={})",
                        event.notificationId(), event.tenantId());
                meters.counter("notify.delivery.results", "channel", channelName(), "outcome", "absent").increment();
                acknowledge(record, acknowledgment);
            }
            case TERMINAL, STALE_GENERATION -> {
                log.info("Dispatch record discarded: {} (notificationId={}, generation={})",
                        disposition, event.notificationId(), event.generation());
                meters.counter("notify.delivery.results", "channel", channelName(), "outcome", "discarded").increment();
                acknowledge(record, acknowledgment);
            }
            case RETRY_PENDING -> {
                NotificationEvidence row = evidence.orElseThrow();
                repairRetryTrigger(event.tenantId(), event.notificationId(), row.dispatchGeneration(),
                        row.nextRetryAt(), traceparent(record));
                acknowledge(record, acknowledgment);
            }
            case GENERATION_AHEAD -> throw new WorkerExceptions.Ordering(
                    "Event generation " + event.generation() + " is ahead of the database", null);
            case CLAIM_RACE -> throw new WorkerExceptions.Ordering(
                    "Claim lost a race for notification " + event.notificationId(), null);
            case IN_PROGRESS_ELSEWHERE -> throw new WorkerExceptions.Unresolved(
                    "Notification " + event.notificationId() + " is PROCESSING at generation " + event.generation(), null);
            case UNEXPECTED_STATE -> throw new WorkerExceptions.NonTransient(
                    "Notification " + event.notificationId() + " is in a state a dispatch event should never meet: "
                            + evidence.map(NotificationEvidence::status).orElse(null), null);
        }
    }

    // ─── post-commit (never changes the result; never blocks the acknowledgment) ────────────────────────────────────

    private void postCommit(ClaimSnapshot claim, PersistableOutcome outcome, String traceparent) {
        Instant nextRetryAt = switch (outcome) {
            case PersistableOutcome.Retry r -> r.nextRetryAt();
            case PersistableOutcome.Defer d -> d.nextRetryAt();
            default -> null;
        };
        if (nextRetryAt != null) {
            repairRetryTrigger(claim.tenantId(), claim.id(), claim.generation(), nextRetryAt,
                    claim.traceContext() != null ? claim.traceContext() : traceparent);
        }
        try {
            afterResultCommitted(claim, outcome);
        } catch (Exception e) {
            // A post-commit hook can never change a committed result, so it can never fail the record either.
            meters.counter("notify.delivery.post_commit_failures", "channel", channelName()).increment();
            log.warn("Post-commit hook failed for notification {} ({}): {}",
                    claim.id(), channelName(), e.getClass().getName());
        }
    }

    /**
     * Channel-specific post-commit step (/ ). Runs after the result transaction committed, and only for a
     * durable outcome - {@code PERSISTED} or {@code RECONCILED_COMMITTED}, never {@code DISCARDED}. It must not
     * change the result and must not fail the record; the caller swallows and counts any exception.
     *
     * <p>IN_APP overrides it to publish a Redis wake-up hint (). The other channels keep the no-op.
     */
    protected void afterResultCommitted(ClaimSnapshot claim, PersistableOutcome outcome) {
        // no-op by default
    }

    /**
     * Schedules the Quartz retry-due trigger if missing. The existing reconciler restores a missing trigger within
     * 60 s, so a failure here is logged and counted but never fails the record. {@code scheduleNotificationRetry} is not
     * idempotent (it throws when the job exists), hence the existence check first.
     */
    private void repairRetryTrigger(UUID tenantId, UUID notificationId, int generation,
                                    Instant nextRetryAt, String traceparent) {
        try {
            if (!scheduler.hasRetryTrigger(notificationId)) {
                scheduler.scheduleNotificationRetry(tenantId, notificationId, nextRetryAt, generation, traceparent);
            }
        } catch (Exception e) {
            meters.counter("notify.worker.quartz_failures", "channel", channelName()).increment();
            log.warn("Retry trigger could not be scheduled; the reconciler will repair it (notificationId={}): {}",
                    notificationId, e.getClass().getName());
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────────────────────────────

    /** Acknowledges the offset. A failure is BROKER_TRANSIENT, never NON_TRANSIENT; the redelivery acks without a second send. */
    private void acknowledge(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        try {
            acknowledgment.acknowledge();
        } catch (RuntimeException e) {
            throw new WorkerExceptions.BrokerTransient("Acknowledgment failed", e);
        }
        controller.onProgress(new TopicPartition(record.topic(), record.partition()));
    }

    private void countOutcome(PersistableOutcome outcome, boolean reconciled) {
        String name = switch (outcome) {
            case PersistableOutcome.Delivered d -> "delivered";
            case PersistableOutcome.Retry r -> "retry";
            case PersistableOutcome.Fail f -> "failed";
            case PersistableOutcome.Defer d -> "deferred";
        };
        meters.counter("notify.delivery.results", "channel", channelName(), "outcome", name).increment();
        if (reconciled) {
            meters.counter("notify.delivery.results", "channel", channelName(), "outcome", "reconciled").increment();
        }
    }

    protected static String traceparent(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader("traceparent");
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying result persistence", e);
        }
    }
}
