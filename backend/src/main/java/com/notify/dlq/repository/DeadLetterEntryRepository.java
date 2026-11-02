package com.notify.dlq.repository;

import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.common.model.DlqStatus;
import com.notify.dlq.entity.DeadLetterEntryEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dead-letter entries. One row per notification for its whole lifetime ({@code notification_id} UNIQUE).
 *
 * <p>The additions are the <b>locked</b> read used to classify a materialization before any lifecycle write
 * (, plan 2.2.1), the two conditional writes, and the keyset search behind the admin list.
 */
@Repository
public interface DeadLetterEntryRepository
        extends JpaRepository<DeadLetterEntryEntity, UUID>, DeadLetterEntrySearch {

    Optional<DeadLetterEntryEntity> findByNotificationId(UUID notificationId);

    Optional<DeadLetterEntryEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * The entry row, locked for the duration of the transaction.
     *
     * <p>Taken <b>after</b> the notification row and <b>before</b> any write, so the outcome of a materialization
     * is decided on stable rows rather than by attempting a write and interpreting the row count. Lock order is
     * fixed project-wide: {@code tenants}, {@code notifications}, {@code dead_letter_entries}, then append-only
     * writes.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DeadLetterEntryEntity d WHERE d.notificationId = :notificationId")
    Optional<DeadLetterEntryEntity> findByNotificationIdForUpdate(@Param("notificationId") UUID notificationId);

    /**
     * Reopens an existing entry for a newer delivery cycle (as amended by ).
     *
     * <p>{@code replay_count} is deliberately <b>not</b> touched here: the single increment lives in the admin
     * replay transaction, so a replay that fails again is counted once, at the replay.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE DeadLetterEntryEntity d
        SET d.status = com.notify.common.model.DlqStatus.OPEN,
            d.reason = :reason,
            d.lastErrorCode = :lastErrorCode,
            d.attemptCount = :attemptCount,
            d.lastEventId = :eventId,
            d.sourceDeliveryCycle = :eventCycle,
            d.sourceGeneration = :eventGeneration,
            d.payloadSnapshot = :payloadSnapshot,
            d.resolutionNote = null,
            d.resolvedBy = null,
            d.resolvedAt = null,
            d.updatedAt = :now
        WHERE d.notificationId = :notificationId
          AND d.tenantId = :tenantId
          AND (d.sourceDeliveryCycle IS NOT NULL AND d.sourceDeliveryCycle < :eventCycle)
    """)
    int reopenForNewerCycleConditional(
            @Param("notificationId") UUID notificationId,
            @Param("tenantId") UUID tenantId,
            @Param("reason") DlqReason reason,
            @Param("lastErrorCode") String lastErrorCode,
            @Param("attemptCount") int attemptCount,
            @Param("eventId") UUID eventId,
            @Param("eventCycle") int eventCycle,
            @Param("eventGeneration") int eventGeneration,
            @Param("payloadSnapshot") String payloadSnapshot,
            @Param("now") Instant now
    );

    /**
     * Marks an OPEN entry resolved by an admin action. Conditional on OPEN, so concurrent replay and discard
     * produce exactly one winner ().
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE DeadLetterEntryEntity d
        SET d.status = :target,
            d.replayCount = d.replayCount + :replayIncrement,
            d.resolutionNote = :note,
            d.resolvedBy = :resolvedBy,
            d.resolvedAt = :now,
            d.updatedAt = :now
        WHERE d.id = :id
          AND d.tenantId = :tenantId
          AND d.status = com.notify.common.model.DlqStatus.OPEN
    """)
    int resolveOpenConditional(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("target") DlqStatus target,
            @Param("replayIncrement") int replayIncrement,
            @Param("note") String note,
            @Param("resolvedBy") String resolvedBy,
            @Param("now") Instant now
    );

}
