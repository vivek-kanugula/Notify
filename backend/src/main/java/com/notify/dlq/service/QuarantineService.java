package com.notify.dlq.service;

import com.notify.dlq.dto.DeadLetterEventEnvelope;
import com.notify.dlq.entity.DlqQuarantineEntity;
import com.notify.dlq.model.QuarantineAssociation;
import com.notify.dlq.model.QuarantineStatus;
import com.notify.dlq.repository.DlqQuarantineRepository;
import com.notify.dlq.service.DeadLetterMaterializationService.RecordCoordinates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Writes quarantine rows (/ , ).
 *
 * <p><b>What is stored, and what is deliberately not.</b> The record coordinates, the DLT reason, the error
 * category, the exception class name, a SHA-256 of the record value and its byte count - and **never the value
 * itself**. A poison payload is arbitrary untrusted input; the dispatch envelope carries no body anyway, and this
 * table must not become the place where such content lands.
 *
 * <p><b>Claimed versus verified.</b> Anything taken from a payload is written to a {@code claimed*} column and is
 * never used for authorization, filtering or as the tenant of record. A {@code verified*} column is written only
 * from a database lookup. The dispatch envelope has no delivery cycle, so none is recorded or reconstructed.
 *
 * <p><b>Idempotent by construction.</b> The unique constraint on topic, partition and offset makes a redelivered
 * record a conflict rather than a second row, so the duplicate simply finds the row already present.
 */
@Service
public class QuarantineService {

    private static final Logger log = LoggerFactory.getLogger(QuarantineService.class);

    private final DlqQuarantineRepository quarantine;
    private final Clock clock;

    public QuarantineService(DlqQuarantineRepository quarantine, Clock clock) {
        this.quarantine = quarantine;
        this.clock = clock;
    }

    /**
     * A dead-lettered dispatch record (): quarantine only, for every association, including RESOLVED.
     *
     * <p>No lifecycle mutation, no business entry and no outbox event - a DLT record proves association, never
     * causation, and the authoritative reason belongs to the committed delivery or reaper result.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public DlqQuarantineEntity recordDltRecord(
            RecordCoordinates coordinates,
            String dltReason,
            String errorCategory,
            String exceptionClass,
            QuarantineAssociation association,
            UUID claimedTenantId,
            UUID claimedNotificationId,
            Integer claimedGeneration,
            UUID verifiedTenantId,
            UUID verifiedNotificationId
    ) {
        return existingOr(coordinates, () -> {
            DlqQuarantineEntity row = baseRow(coordinates, dltReason, association);
            row.setErrorCategory(errorCategory);
            row.setExceptionClass(exceptionClass);
            row.setClaimedTenantId(claimedTenantId);
            row.setClaimedNotificationId(claimedNotificationId);
            row.setClaimedGeneration(claimedGeneration);
            row.setVerifiedTenantId(verifiedTenantId);
            row.setVerifiedNotificationId(verifiedNotificationId);
            return row;
        });
    }

    /**
     * An authoritative dead-letter event that could not be applied (plan 2.2 outcome QUARANTINE).
     *
     * <p>Recorded instead of silently acknowledged, so "cannot be explained" never looks like "already handled".
     * The notification, any existing entry, the history and {@code replay_count} are untouched by this method.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public DlqQuarantineEntity recordUnappliedDeadLetterEvent(
            DeadLetterEventEnvelope event,
            RecordCoordinates coordinates,
            QuarantineAssociation association
    ) {
        return existingOr(coordinates, () -> {
            DlqQuarantineEntity row = baseRow(coordinates, "DLQ_EVENT_NOT_APPLIED", association);
            row.setClaimedTenantId(event.tenantId());
            row.setClaimedNotificationId(event.notificationId());
            row.setClaimedGeneration(event.generation());
            if (association == QuarantineAssociation.RESOLVED) {
                // The notification exists and the tenant matched; the event still could not be applied.
                row.setVerifiedTenantId(event.tenantId());
                row.setVerifiedNotificationId(event.notificationId());
            }
            return row;
        });
    }

    /** A record on either topic whose value cannot be parsed at all. No identifier is invented. */
    @Transactional(propagation = Propagation.REQUIRED)
    public DlqQuarantineEntity recordUnparsable(
            RecordCoordinates coordinates, String dltReason, String exceptionClass) {
        return existingOr(coordinates, () -> {
            DlqQuarantineEntity row = baseRow(coordinates, dltReason, QuarantineAssociation.UNPARSABLE);
            row.setExceptionClass(exceptionClass);
            return row;
        });
    }

    private DlqQuarantineEntity existingOr(
            RecordCoordinates coordinates, java.util.function.Supplier<DlqQuarantineEntity> factory) {
        return quarantine.findByTopicAndPartitionNumberAndRecordOffset(
                        coordinates.topic(), coordinates.partition(), coordinates.offset())
                .map(existing -> {
                    log.debug("Quarantine row already present for {}-{}@{}",
                            coordinates.topic(), coordinates.partition(), coordinates.offset());
                    return existing;
                })
                .orElseGet(() -> quarantine.save(factory.get()));
    }

    private DlqQuarantineEntity baseRow(
            RecordCoordinates coordinates, String dltReason, QuarantineAssociation association) {
        DlqQuarantineEntity row = new DlqQuarantineEntity();
        row.setTopic(coordinates.topic());
        row.setPartitionNumber(coordinates.partition());
        row.setRecordOffset(coordinates.offset());
        row.setDltReason(dltReason);
        row.setAssociation(association);
        row.setStatus(QuarantineStatus.OPEN);
        row.setPayloadSha256(sha256(coordinates.value()));
        row.setPayloadByteCount(coordinates.value() == null ? 0 : coordinates.value().length);
        row.setCreatedAt(clock.instant());
        return row;
    }

    /** A digest, never the bytes. */
    private static String sha256(byte[] value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /** Only used by the tests that assert the digest is of the exact record value. */
    public static String digestOf(String value) {
        return sha256(value == null ? null : value.getBytes(StandardCharsets.UTF_8));
    }
}
