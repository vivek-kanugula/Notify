package com.notify.dlq.service;

import com.notify.common.model.DlqStatus;
import com.notify.dlq.dto.DeadLetterEventEnvelope;
import com.notify.dlq.entity.DeadLetterEntryEntity;
import com.notify.dlq.model.MaterializationOutcome;
import com.notify.dlq.model.QuarantineAssociation;
import com.notify.dlq.repository.DeadLetterEntryRepository;
import com.notify.notification.dto.DeadLetterDecisionView;
import com.notify.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Applies one authoritative {@code notify.dlq.v1} event (/ , ).
 *
 * <p><b>Locked classification, then exactly one write path.</b> The notification row is locked first, the entry row
 * second - the project lock order - and the outcome is decided on those stable rows <em>before</em> any lifecycle
 * write. Nothing tentative is ever written: a quarantine-only outcome leaves the notification, any existing entry,
 * the history and {@code replay_count} untouched, which a rollback-based design could not guarantee, because the
 * rollback would discard the quarantine row as well.
 *
 * <p><b>The outcome matrix is exhaustive</b> (plan section 2.2). {@code DUPLICATE} and {@code STALE} are the only
 * silent no-ops and both are proofs: the entry already records this event id, or the row demonstrably moved past
 * this cycle. Everything else that cannot be applied is {@code QUARANTINED}, so a record that cannot be explained
 * never looks like a record that was already handled.
 */
@Service
public class DeadLetterMaterializationService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterMaterializationService.class);

    private final NotificationService notificationService;
    private final DeadLetterEntryRepository entries;
    private final QuarantineService quarantine;
    private final Clock clock;

    public DeadLetterMaterializationService(
            NotificationService notificationService,
            DeadLetterEntryRepository entries,
            QuarantineService quarantine,
            Clock clock
    ) {
        this.notificationService = notificationService;
        this.entries = entries;
        this.quarantine = quarantine;
        this.clock = clock;
    }

    /**
     * One transaction per record. The caller acknowledges the Kafka offset only after this returns and the
     * transaction commits.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public MaterializationOutcome apply(DeadLetterEventEnvelope event, RecordCoordinates coordinates) {
        Optional<DeadLetterDecisionView> locked =
                notificationService.lockForDeadLetterDecision(event.notificationId());

        // Row 1: no notification, or a tenant the database does not agree with. The payload tenant is never
        // trusted as the tenant of record.
        if (locked.isEmpty()) {
            return quarantineOnly(event, coordinates, QuarantineAssociation.UNKNOWN_NOTIFICATION,
                    "no notification with this id");
        }
        DeadLetterDecisionView notification = locked.get();
        if (!notification.tenantId().equals(event.tenantId())) {
            return quarantineOnly(event, coordinates, QuarantineAssociation.TENANT_MISMATCH,
                    "event tenant does not own this notification");
        }

        Optional<DeadLetterEntryEntity> existing = entries.findByNotificationIdForUpdate(event.notificationId());

        // Row 2: the entry already records this exact event. A proven duplicate, whatever the row did since.
        if (existing.isPresent() && event.eventId().equals(existing.get().getLastEventId())) {
            log.debug("Dead-letter event {} is a duplicate for notification {}",
                    event.eventId(), event.notificationId());
            return MaterializationOutcome.DUPLICATE;
        }

        int eventCycle = event.deliveryCycle();
        Integer recordedCycle = existing.map(DeadLetterEntryEntity::getSourceDeliveryCycle).orElse(null);

        // Row 3: the notification moved to a later cycle. Proven superseded.
        if (notification.deliveryCycle() > eventCycle) {
            log.debug("Dead-letter event {} is stale: notification {} is at cycle {}, event carries {}",
                    event.eventId(), event.notificationId(), notification.deliveryCycle(), eventCycle);
            return MaterializationOutcome.STALE;
        }

        // Row 4: an event from a cycle the row never reached - impossible without data loss, so it is reported.
        if (notification.deliveryCycle() < eventCycle) {
            return quarantineOnly(event, coordinates, QuarantineAssociation.RESOLVED,
                    "event cycle is ahead of the notification cycle");
        }

        // Row 5: same cycle, different generation - the association cannot be reconciled.
        if (notification.dispatchGeneration() != event.generation()) {
            return quarantineOnly(event, coordinates, QuarantineAssociation.RESOLVED,
                    "event generation does not match the notification generation for this cycle");
        }

        return switch (notification.status()) {
            case FAILED -> applyToFailed(event, coordinates, notification, existing, recordedCycle);
            case DLQ -> applyToDlq(event, coordinates, existing, recordedCycle, eventCycle);
            // Row 12: the association is real but the state contradicts the event.
            default -> quarantineOnly(event, coordinates, QuarantineAssociation.RESOLVED,
                    "notification is " + notification.status() + " for this cycle, not FAILED");
        };
    }

    /** Rows 6 to 9: the notification is FAILED for this cycle and generation. */
    private MaterializationOutcome applyToFailed(
            DeadLetterEventEnvelope event,
            RecordCoordinates coordinates,
            DeadLetterDecisionView notification,
            Optional<DeadLetterEntryEntity> existing,
            Integer recordedCycle
    ) {
        if (existing.isEmpty()) {
            return materializeFirstEntry(event, notification);
        }
        if (recordedCycle == null) {
            // Row 9: a legacy or partially written entry. Nothing proves duplicate, stale or reopen, so nothing is
            // assumed - the operator inspects it.
            return quarantineOnly(event, coordinates, QuarantineAssociation.RESOLVED,
                    "existing entry has no recorded source cycle");
        }
        if (recordedCycle < event.deliveryCycle()) {
            return reopen(event);
        }
        // Row 8: an entry already records this cycle or a later one while the notification is still FAILED, which
        // a committed materialization would have left as DLQ. Contradictory.
        return quarantineOnly(event, coordinates, QuarantineAssociation.RESOLVED,
                "entry already records cycle " + recordedCycle + " while the notification is still FAILED");
    }

    /** Rows 10 and 11: the notification is already DLQ for this cycle. */
    private MaterializationOutcome applyToDlq(
            DeadLetterEventEnvelope event,
            RecordCoordinates coordinates,
            Optional<DeadLetterEntryEntity> existing,
            Integer recordedCycle,
            int eventCycle
    ) {
        if (existing.isPresent() && recordedCycle != null && recordedCycle >= eventCycle) {
            return MaterializationOutcome.STALE;
        }
        return quarantineOnly(event, coordinates, QuarantineAssociation.RESOLVED,
                existing.isEmpty()
                        ? "notification is DLQ with no entry to explain it"
                        : "entry cannot account for this event");
    }

    private MaterializationOutcome materializeFirstEntry(
            DeadLetterEventEnvelope event, DeadLetterDecisionView notification) {
        requireTransition(event);
        Instant now = clock.instant();
        DeadLetterEntryEntity entry = new DeadLetterEntryEntity();
        entry.setTenantId(event.tenantId());
        entry.setNotificationId(event.notificationId());
        entry.setChannel(notification.channel());
        entry.setReason(event.reason());
        entry.setLastErrorCode(event.lastErrorCode());
        entry.setAttemptCount(event.attemptCount());
        entry.setStatus(DlqStatus.OPEN);
        entry.setLastEventId(event.eventId());
        entry.setSourceDeliveryCycle(event.deliveryCycle());
        entry.setSourceGeneration(event.generation());
        entry.setPayloadSnapshot(DeadLetterSnapshots.of(event));
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        entries.save(entry);
        log.info("Materialized dead-letter entry for notification {} (cycle {}, reason {})",
                event.notificationId(), event.deliveryCycle(), event.reason());
        return MaterializationOutcome.MATERIALIZED;
    }

    private MaterializationOutcome reopen(DeadLetterEventEnvelope event) {
        requireTransition(event);
        int rows = entries.reopenForNewerCycleConditional(
                event.notificationId(), event.tenantId(), event.reason(), event.lastErrorCode(),
                event.attemptCount(), event.eventId(), event.deliveryCycle(), event.generation(),
                DeadLetterSnapshots.of(event), clock.instant());
        if (rows == 0) {
            // The entry was locked and its cycle checked, so this cannot happen. Aborting is still correct: the
            // transaction rolls back whole and the record is redelivered rather than half applied.
            throw new IllegalStateException(
                    "Entry reopen updated no row for notification " + event.notificationId());
        }
        log.info("Reopened dead-letter entry for notification {} at cycle {} (reason {}); replay_count untouched",
                event.notificationId(), event.deliveryCycle(), event.reason());
        return MaterializationOutcome.REOPENED;
    }

    /**
     * Performs the FAILED to DLQ transition and refuses to continue if it updated no row.
     *
     * <p>The row was locked and read as FAILED for this cycle and generation, so zero rows means an invariant is
     * broken. Throwing rolls back the whole transaction - no entry, no history row, no acknowledgment - which is
     * the behaviour pins: there is never a DLQ notification without its entry, nor an entry for a
     * notification still FAILED.
     */
    private void requireTransition(DeadLetterEventEnvelope event) {
        boolean transitioned = notificationService.materializeDeadLetter(
                event.tenantId(), event.notificationId(), event.deliveryCycle(), event.generation(), event.reason());
        if (!transitioned) {
            throw new IllegalStateException(
                    "Conditional dead-letter transition updated no row for notification " + event.notificationId());
        }
    }

    /**
     * The quarantine-only path: one quarantine row and nothing else. No notification write, no entry write, no
     * history row, no {@code replay_count} change.
     */
    private MaterializationOutcome quarantineOnly(
            DeadLetterEventEnvelope event,
            RecordCoordinates coordinates,
            QuarantineAssociation association,
            String detail
    ) {
        log.warn("Dead-letter event {} for notification {} cannot be applied ({}): quarantined as {}",
                event.eventId(), event.notificationId(), detail, association);
        quarantine.recordUnappliedDeadLetterEvent(event, coordinates, association);
        return MaterializationOutcome.QUARANTINED;
    }

    /** Where a record came from. The triple is the record identity, so a redelivery is an insert conflict. */
    public record RecordCoordinates(String topic, int partition, long offset, byte[] value) {
    }
}
