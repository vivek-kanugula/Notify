package com.notify.notification.model;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.Actor;
import com.notify.common.model.NotificationStatus;
import com.notify.notification.dto.ClaimResult;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.entity.NotificationStatusHistoryEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.repository.NotificationStatusHistoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Row-count checked conditional notification state transition engine.
 * Ensures that state transitions are verified by NotificationStateMachine,
 * conditionally executed against the database with dispatch generation fencing,
 * and logged to notification_status_history.
 */
@Component
public class NotificationTransitioner {

    private final NotificationRepository notificationRepository;
    private final NotificationStatusHistoryRepository statusHistoryRepository;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public NotificationTransitioner(
            NotificationRepository notificationRepository,
            NotificationStatusHistoryRepository statusHistoryRepository,
            org.springframework.context.ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.notificationRepository = notificationRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public void transition(
            UUID tenantId,
            UUID notificationId,
            NotificationStatus fromStatus,
            NotificationStatus toStatus,
            Actor actor,
            int expectedGeneration,
            String reason
    ) {
        // 1. Validate transition legality by state machine rules
        NotificationStateMachine.validateTransition(fromStatus, toStatus, actor);

        // 2. Perform conditional update with row count check
        Instant now = clock.instant();
        int updatedRows;
        if (fromStatus == NotificationStatus.RETRYING && toStatus == NotificationStatus.PROCESSING) {
            // Guard latent early claim: enforce due-time fence (next_retry_at <= now)
            updatedRows = notificationRepository.claimRetryingToProcessingConditional(
                    notificationId, tenantId, expectedGeneration, now
            );
        } else {
            updatedRows = notificationRepository.updateStatusConditional(
                    notificationId, tenantId, fromStatus, toStatus, expectedGeneration, now
            );
        }

        if (updatedRows == 0) {
            throw new NotifyException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    HttpStatus.CONFLICT,
                    String.format("Failed to transition notification %s from %s to %s with generation %d: condition not met",
                            notificationId, fromStatus, toStatus, expectedGeneration)
            );
        }

        // 3. Write immutable audit history
        NotificationStatusHistoryEntity history = new NotificationStatusHistoryEntity();
        history.setTenantId(tenantId);
        history.setNotificationId(notificationId);
        history.setFromStatus(fromStatus);
        history.setToStatus(toStatus);
        history.setActor(actor);
        history.setReason(reason);
        history.setOccurredAt(now);
        statusHistoryRepository.save(history);
        eventPublisher.publishEvent(new com.notify.notification.event.NotificationTransitionedEvent(
                tenantId, notificationId, fromStatus, toStatus, actor, expectedGeneration, now
        ));
    }

    @Transactional
    public void cancel(
            UUID tenantId,
            UUID notificationId,
            NotificationStatus fromStatus,
            Actor actor,
            int expectedGeneration,
            String reason
    ) {
        // 1. Validate transition legality by state machine rules
        NotificationStateMachine.validateTransition(fromStatus, NotificationStatus.CANCELLED, actor);

        // 2. Perform conditional update with row count check
        Instant now = clock.instant();
        int updatedRows = notificationRepository.cancelConditional(
                notificationId, tenantId, fromStatus, expectedGeneration, reason, now
        );

        NotificationStatus effectiveFromStatus = fromStatus;
        int effectiveGeneration = expectedGeneration;

        // Scheduled-release vs tenant-cancel race recovery:
        // If cancellation from SCHEDULED failed because concurrent release transitioned to QUEUED,
        // retry cancellation against QUEUED if permitted by the state machine.
        if (updatedRows == 0 && fromStatus == NotificationStatus.SCHEDULED) {
            NotificationEntity current = notificationRepository.findByIdAndTenantId(notificationId, tenantId).orElse(null);
            if (current != null 
                    && current.getStatus() == NotificationStatus.QUEUED
                    && NotificationStateMachine.isValidTransition(NotificationStatus.QUEUED, NotificationStatus.CANCELLED, actor)) {
                effectiveFromStatus = NotificationStatus.QUEUED;
                effectiveGeneration = current.getDispatchGeneration();
                now = clock.instant();
                updatedRows = notificationRepository.cancelConditional(
                        notificationId, tenantId, effectiveFromStatus, effectiveGeneration, reason, now
                );
            }
        }

        if (updatedRows == 0) {
            throw new NotifyException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    HttpStatus.CONFLICT,
                    String.format("Failed to cancel notification %s from %s with generation %d: condition not met",
                            notificationId, effectiveFromStatus, effectiveGeneration)
            );
        }

        // 3. Write immutable audit history (exactly one row)
        NotificationStatusHistoryEntity history = new NotificationStatusHistoryEntity();
        history.setTenantId(tenantId);
        history.setNotificationId(notificationId);
        history.setFromStatus(effectiveFromStatus);
        history.setToStatus(NotificationStatus.CANCELLED);
        history.setActor(actor);
        history.setReason(reason);
        history.setOccurredAt(now);
        statusHistoryRepository.save(history);

        // 4. Publish event within the transaction: transactional listeners observe once after commit, none on rollback
        eventPublisher.publishEvent(new com.notify.notification.event.NotificationTransitionedEvent(
                tenantId, notificationId, effectiveFromStatus, NotificationStatus.CANCELLED, actor, effectiveGeneration, now
        ));
    }

    @Transactional
    public boolean releaseRetry(
            UUID tenantId,
            UUID notificationId,
            int expectedGeneration,
            String reason
    ) {
        // 1. Validate transition legality by state machine rules (/ )
        NotificationStateMachine.validateTransition(
                NotificationStatus.RETRYING, NotificationStatus.QUEUED, Actor.SYSTEM
        );

        // 2. Perform conditional update with row count, due-time, and dispatch-generation fencing
        Instant now = clock.instant();
        int updatedRows = notificationRepository.releaseRetryConditional(
                notificationId, tenantId, expectedGeneration, now
        );

        if (updatedRows == 0) {
            return false;
        }

        // 3. Write immutable audit history
        NotificationStatusHistoryEntity history = new NotificationStatusHistoryEntity();
        history.setTenantId(tenantId);
        history.setNotificationId(notificationId);
        history.setFromStatus(NotificationStatus.RETRYING);
        history.setToStatus(NotificationStatus.QUEUED);
        history.setActor(Actor.SYSTEM);
        history.setReason(reason);
        history.setOccurredAt(now);
        statusHistoryRepository.save(history);

        // 4. Publish event within the transaction
        eventPublisher.publishEvent(new com.notify.notification.event.NotificationTransitionedEvent(
                tenantId, notificationId, NotificationStatus.RETRYING, NotificationStatus.QUEUED,
                Actor.SYSTEM, expectedGeneration + 1, now
        ));

        return true;
    }

    /**
     * Worker claim (/ ). Keeps the predicate: QUEUED, or RETRYING when due, fenced by
     * {@code dispatch_generation}. Increments {@code attempt_count} and {@code claim_seq}; the first statement that
     * updates a row decides the history {@code from} status. Zero rows is a normal result, never an exception,
     * and never marks the transaction rollback-only. Runs in its own short transaction (bounded to 5 s).
     *
     * @param defaultSender sender written only when the row has none (legacy rows); {@code null} leaves it unchanged
     */
    @Transactional(timeout = 5)
    public ClaimResult claim(UUID tenantId, UUID notificationId, int expectedGeneration, String defaultSender) {
        NotificationStateMachine.validateTransition(NotificationStatus.QUEUED, NotificationStatus.PROCESSING, Actor.SYSTEM);
        NotificationStateMachine.validateTransition(NotificationStatus.RETRYING, NotificationStatus.PROCESSING, Actor.SYSTEM);

        Instant now = clock.instant();
        NotificationStatus fromStatus = NotificationStatus.QUEUED;
        int updatedRows = notificationRepository.claimQueuedForDelivery(
                notificationId, tenantId, expectedGeneration, defaultSender, now);
        if (updatedRows == 0) {
            fromStatus = NotificationStatus.RETRYING;
            updatedRows = notificationRepository.claimDueRetryingForDelivery(
                    notificationId, tenantId, expectedGeneration, defaultSender, now);
        }
        if (updatedRows == 0) {
            return ClaimResult.Unclaimed.INSTANCE;
        }

        NotificationEntity row = notificationRepository.findByIdAndTenantId(notificationId, tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "Notification missing after successful claim update: " + notificationId));

        recordTransition(tenantId, notificationId, fromStatus, NotificationStatus.PROCESSING,
                expectedGeneration, "Worker claim", now);

        ClaimSnapshot snapshot = new ClaimSnapshot(
                row.getId(), row.getTenantId(), row.getDispatchGeneration(), row.getClaimSeq(),
                row.getDeliveryCycle(), row.getAttemptCount(), row.getMaxAttempts(), row.getDeferralCount(),
                row.getChannel(), row.getRecipient(), row.getSubject(), row.getBody(), row.getMetadata(),
                row.getTraceContext(), row.getSender(), row.getQueuedAt(), row.getCreatedAt());
        return new ClaimResult.Claimed(snapshot, fromStatus);
    }

    /** PROCESSING to DELIVERED, fenced by generation and claim_seq. Joins the caller's transaction. Returns false on 0 rows. */
    @Transactional
    public boolean completeDelivery(
            UUID tenantId, UUID notificationId, int expectedGeneration, int claimSeq,
            String provider, String providerMessageId
    ) {
        NotificationStateMachine.validateTransition(NotificationStatus.PROCESSING, NotificationStatus.DELIVERED, Actor.SYSTEM);
        Instant now = clock.instant();
        int rows = notificationRepository.completeDeliveryConditional(
                notificationId, tenantId, expectedGeneration, claimSeq, provider, providerMessageId, now);
        if (rows == 0) {
            return false;
        }
        recordTransition(tenantId, notificationId, NotificationStatus.PROCESSING, NotificationStatus.DELIVERED,
                expectedGeneration, "Provider accepted", now);
        return true;
    }

    /** PROCESSING to FAILED (permanent or exhausted), fenced by generation and claim_seq. Returns false on 0 rows. */
    @Transactional
    public boolean failDelivery(
            UUID tenantId, UUID notificationId, int expectedGeneration, int claimSeq, String errorCode
    ) {
        NotificationStateMachine.validateTransition(NotificationStatus.PROCESSING, NotificationStatus.FAILED, Actor.SYSTEM);
        Instant now = clock.instant();
        int rows = notificationRepository.failDeliveryConditional(
                notificationId, tenantId, expectedGeneration, claimSeq, errorCode, now);
        if (rows == 0) {
            return false;
        }
        recordTransition(tenantId, notificationId, NotificationStatus.PROCESSING, NotificationStatus.FAILED,
                expectedGeneration, "Failed: " + errorCode, now);
        return true;
    }

    /** What a reap did to a stuck claim. */
    public enum ReapOutcome {
        /** Budget remained: the row is RETRYING and due now, so the existing retry path carries it to QUEUED. */
        RESCHEDULED,
        /** Budget was spent: the row is FAILED and the caller must write the dead-letter event in this transaction. */
        EXHAUSTED,
        /** Nothing was reaped: another reaper won, the claim moved on, or the row is no longer stuck. */
        NOT_STUCK
    }

    /**
     * Recovers one stuck PROCESSING claim (/ , ).
     *
     * <p>Two terminal paths, each chosen <b>atomically</b> by its own conditional update, because the attempt budget
     * is part of the predicate: a claim with budget left becomes RETRYING and due immediately, and a claim whose
     * budget is spent becomes FAILED. An unconditional move to RETRYING would let the due path claim the row again
     * and send past {@code max_attempts}.
     *
     * <p>No {@code dispatch_generation} bump (). Moving the row out of PROCESSING is already enough to fence a
     * slow original worker, because every result write - {@code completeDelivery}, {@code retryDelivery},
     * {@code failDelivery}, {@code deferDelivery} - requires {@code status = PROCESSING} together with the matching
     * generation and {@code claim_seq}. The original worker's commit therefore updates zero rows, raises
     * {@link com.notify.delivery.model.FencedOutException}, rolls its whole result back and acks.
     *
     * <p>The attempt the lost claim consumed is <b>not</b> refunded (), and the history reason says the outcome
     * was never observed rather than inventing one. No {@code delivery_attempts} row is written on either path:
     * {@link com.notify.common.model.AttemptOutcome} has no value for "unknown", and recording SUCCESS or a failure
     * would fabricate a provider verdict this system never saw.
     *
     * <p>Joins the caller's transaction, so on the EXHAUSTED path the FAILED status, its history row and the
     * caller's dead-letter event commit or roll back together.
     */
    @Transactional
    public ReapOutcome reapStuckProcessing(
            UUID tenantId, UUID notificationId, int expectedGeneration, int claimSeq,
            Instant cutoff, String errorCode
    ) {
        NotificationStateMachine.validateTransition(
                NotificationStatus.PROCESSING, NotificationStatus.RETRYING, Actor.SYSTEM);
        NotificationStateMachine.validateTransition(
                NotificationStatus.PROCESSING, NotificationStatus.FAILED, Actor.SYSTEM);

        Instant now = clock.instant();
        int rescheduled = notificationRepository.reapStuckProcessingToRetrying(
                notificationId, tenantId, expectedGeneration, claimSeq, cutoff, errorCode, now);
        if (rescheduled > 0) {
            recordTransition(tenantId, notificationId, NotificationStatus.PROCESSING, NotificationStatus.RETRYING,
                    expectedGeneration, "Recovered: " + errorCode, now);
            return ReapOutcome.RESCHEDULED;
        }

        int exhausted = notificationRepository.reapStuckProcessingToFailed(
                notificationId, tenantId, expectedGeneration, claimSeq, cutoff, errorCode, now);
        if (exhausted > 0) {
            recordTransition(tenantId, notificationId, NotificationStatus.PROCESSING, NotificationStatus.FAILED,
                    expectedGeneration, "Recovered, budget spent: " + errorCode, now);
            return ReapOutcome.EXHAUSTED;
        }
        return ReapOutcome.NOT_STUCK;
    }

    /** PROCESSING to RETRYING (retryable failure), fenced by generation and claim_seq. Returns false on 0 rows. */
    @Transactional
    public boolean retryDelivery(
            UUID tenantId, UUID notificationId, int expectedGeneration, int claimSeq,
            Instant nextRetryAt, String errorCode
    ) {
        NotificationStateMachine.validateTransition(NotificationStatus.PROCESSING, NotificationStatus.RETRYING, Actor.SYSTEM);
        Instant now = clock.instant();
        int rows = notificationRepository.retryDeliveryConditional(
                notificationId, tenantId, expectedGeneration, claimSeq, nextRetryAt, errorCode, now);
        if (rows == 0) {
            return false;
        }
        recordTransition(tenantId, notificationId, NotificationStatus.PROCESSING, NotificationStatus.RETRYING,
                expectedGeneration, "Retry scheduled: " + errorCode, now);
        return true;
    }

    /**
     * PROCESSING to RETRYING as a deferral: refunds the attempt consumed by the claim and counts the deferral.
     * Fenced by generation and claim_seq; claim_seq itself is never modified. Returns false on 0 rows.
     */
    @Transactional
    public boolean deferDelivery(
            UUID tenantId, UUID notificationId, int expectedGeneration, int claimSeq,
            Instant nextRetryAt, String errorCode
    ) {
        NotificationStateMachine.validateTransition(NotificationStatus.PROCESSING, NotificationStatus.RETRYING, Actor.SYSTEM);
        Instant now = clock.instant();
        int rows = notificationRepository.deferDeliveryConditional(
                notificationId, tenantId, expectedGeneration, claimSeq, nextRetryAt, errorCode, now);
        if (rows == 0) {
            return false;
        }
        recordTransition(tenantId, notificationId, NotificationStatus.PROCESSING, NotificationStatus.RETRYING,
                expectedGeneration, "Deferred: " + errorCode, now);
        return true;
    }

    private void recordTransition(
            UUID tenantId, UUID notificationId, NotificationStatus from, NotificationStatus to,
            int generation, String reason, Instant now
    ) {
        recordTransition(tenantId, notificationId, from, to, generation, reason, now, Actor.SYSTEM);
    }

    /**
     * Same history row, with an explicit actor. Added for the admin replay (), which is the first
     * {@code Actor.ADMIN} lifecycle transition in the system; every existing caller keeps {@code Actor.SYSTEM}
     * through the overload above, so no recorded behaviour changes.
     */
    private void recordTransition(
            UUID tenantId, UUID notificationId, NotificationStatus from, NotificationStatus to,
            int generation, String reason, Instant now, Actor actor
    ) {
        NotificationStatusHistoryEntity history = new NotificationStatusHistoryEntity();
        history.setTenantId(tenantId);
        history.setNotificationId(notificationId);
        history.setFromStatus(from);
        history.setToStatus(to);
        history.setActor(actor);
        history.setReason(reason != null && reason.length() > 64 ? reason.substring(0, 64) : reason);
        history.setOccurredAt(now);
        statusHistoryRepository.save(history);
        eventPublisher.publishEvent(new com.notify.notification.event.NotificationTransitionedEvent(
                tenantId, notificationId, from, to, actor, generation, now
        ));
    }

    @Transactional
    public boolean releaseScheduled(
            UUID tenantId,
            UUID notificationId,
            int expectedGeneration,
            String reason
    ) {
        // 1. Validate transition legality by state machine rules
        NotificationStateMachine.validateTransition(
                NotificationStatus.SCHEDULED, NotificationStatus.QUEUED, Actor.SYSTEM
        );

        // 2. Perform conditional update with row count, due-time, and dispatch-generation fencing
        Instant now = clock.instant();
        int updatedRows = notificationRepository.releaseScheduledConditional(
                notificationId, tenantId, expectedGeneration, now
        );

        if (updatedRows == 0) {
            return false;
        }

        // 3. Write immutable audit history
        NotificationStatusHistoryEntity history = new NotificationStatusHistoryEntity();
        history.setTenantId(tenantId);
        history.setNotificationId(notificationId);
        history.setFromStatus(NotificationStatus.SCHEDULED);
        history.setToStatus(NotificationStatus.QUEUED);
        history.setActor(Actor.SYSTEM);
        history.setReason(reason);
        history.setOccurredAt(now);
        statusHistoryRepository.save(history);

        // 4. Publish event within the transaction
        eventPublisher.publishEvent(new com.notify.notification.event.NotificationTransitionedEvent(
                tenantId, notificationId, NotificationStatus.SCHEDULED, NotificationStatus.QUEUED,
                Actor.SYSTEM, expectedGeneration + 1, now
        ));

        return true;
    }

    // ── / (): dead-letter materialization and admin replay ────────────────────────────────────────────

    /**
     * FAILED to DLQ for one authoritative dead-letter event ().
     *
     * <p>Only the {@code notify.dlq.v1} event reaches this method: a DLT record never does, because the DLT flow
     * performs no lifecycle mutation at all (). The conditional update carries the cycle and the generation,
     * so a duplicate, a stale event, an already-replayed row and a delivered row all update zero rows and this
     * returns false without writing a history row.
     */
    @Transactional
    public boolean materializeDeadLetter(
            UUID tenantId, UUID notificationId, int eventCycle, int eventGeneration, String reasonLabel
    ) {
        NotificationStateMachine.validateTransition(
                NotificationStatus.FAILED, NotificationStatus.DLQ, Actor.SYSTEM);
        Instant now = clock.instant();
        int rows = notificationRepository.failedToDlqConditional(
                notificationId, tenantId, eventCycle, eventGeneration, now);
        if (rows == 0) {
            return false;
        }
        recordTransition(tenantId, notificationId, NotificationStatus.FAILED, NotificationStatus.DLQ,
                eventGeneration, "Dead-lettered: " + reasonLabel, now);
        return true;
    }

    /**
     * DLQ to QUEUED on an admin replay (): new delivery cycle, new dispatch generation, cycle budgets reset,
     * {@code claim_seq} preserved.
     *
     * <p>The generation in the predicate is the one the admin read, so a concurrent replay, a concurrent discard
     * that already resolved the entry, or any other transition in between makes this return false and the caller
     * roll the whole transaction back.
     */
    @Transactional
    public boolean replayFromDlq(UUID tenantId, UUID notificationId, int expectedGeneration, String reason) {
        NotificationStateMachine.validateTransition(
                NotificationStatus.DLQ, NotificationStatus.QUEUED, Actor.ADMIN);
        Instant now = clock.instant();
        int rows = notificationRepository.replayFromDlqConditional(
                notificationId, tenantId, expectedGeneration, now);
        if (rows == 0) {
            return false;
        }
        recordTransition(tenantId, notificationId, NotificationStatus.DLQ, NotificationStatus.QUEUED,
                expectedGeneration + 1, reason, now, Actor.ADMIN);
        return true;
    }
}
