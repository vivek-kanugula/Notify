package com.notify.notification.repository;

import com.notify.common.model.Channel;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import com.notify.common.model.NotificationStatus;
import com.notify.notification.dto.NotificationEvidence;
import com.notify.notification.dto.NotificationResponse;
import com.notify.notification.entity.NotificationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository
        extends JpaRepository<NotificationEntity, UUID>, JpaSpecificationExecutor<NotificationEntity> {

    Optional<NotificationEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByIdAndTenantId(UUID id, UUID tenantId);

    Optional<NotificationEntity> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    Page<NotificationEntity> findByTenantId(UUID tenantId, Pageable pageable);

    Page<NotificationEntity> findByTenantIdAndChannel(
            UUID tenantId, Channel channel, Pageable pageable);

    Page<NotificationEntity> findByTenantIdAndStatus(
            UUID tenantId, NotificationStatus status, Pageable pageable);

    Page<NotificationEntity> findByTenantIdAndChannelAndStatus(
            UUID tenantId, Channel channel, NotificationStatus status, Pageable pageable);

    @Query("""
        SELECT new com.notify.notification.dto.NotificationResponse(
            n.id, n.channel, n.status, n.recipient, n.subject, n.scheduledAt, n.createdAt
        )
        FROM NotificationEntity n
        WHERE n.tenantId = :tenantId
    """)
    Page<NotificationResponse> findSummariesByTenantId(
            @Param("tenantId") UUID tenantId, Pageable pageable);

    @Query("""
        SELECT new com.notify.notification.dto.NotificationResponse(
            n.id, n.channel, n.status, n.recipient, n.subject, n.scheduledAt, n.createdAt
        )
        FROM NotificationEntity n
        WHERE n.tenantId = :tenantId
          AND n.channel = :channel
    """)
    Page<NotificationResponse> findSummariesByTenantIdAndChannel(
            @Param("tenantId") UUID tenantId,
            @Param("channel") Channel channel,
            Pageable pageable);

    @Query("""
        SELECT new com.notify.notification.dto.NotificationResponse(
            n.id, n.channel, n.status, n.recipient, n.subject, n.scheduledAt, n.createdAt
        )
        FROM NotificationEntity n
        WHERE n.tenantId = :tenantId
          AND n.status = :status
    """)
    Page<NotificationResponse> findSummariesByTenantIdAndStatus(
            @Param("tenantId") UUID tenantId,
            @Param("status") NotificationStatus status,
            Pageable pageable);

    @Query("""
        SELECT new com.notify.notification.dto.NotificationResponse(
            n.id, n.channel, n.status, n.recipient, n.subject, n.scheduledAt, n.createdAt
        )
        FROM NotificationEntity n
        WHERE n.tenantId = :tenantId
          AND n.channel = :channel
          AND n.status = :status
    """)
    Page<NotificationResponse> findSummariesByTenantIdAndChannelAndStatus(
            @Param("tenantId") UUID tenantId,
            @Param("channel") Channel channel,
            @Param("status") NotificationStatus status,
            Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE NotificationEntity n
        SET n.status = com.notify.common.model.NotificationStatus.CANCELLED,
            n.cancelReason = :reason,
            n.completedAt = :now,
            n.updatedAt = :now,
            n.version = n.version + 1
        WHERE n.id = :id
          AND n.tenantId = :tenantId
          AND n.status = :expectedStatus
          AND n.dispatchGeneration = :expectedGeneration
    """)
    int cancelConditional(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("expectedStatus") NotificationStatus expectedStatus,
            @Param("expectedGeneration") int expectedGeneration,
            @Param("reason") String reason,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE NotificationEntity n
        SET n.status = :toStatus,
            n.updatedAt = :now,
            n.version = n.version + 1
        WHERE n.id = :id
          AND n.tenantId = :tenantId
          AND n.status = :fromStatus
          AND n.dispatchGeneration = :expectedGeneration
    """)
    int updateStatusConditional(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("fromStatus") NotificationStatus fromStatus,
            @Param("toStatus") NotificationStatus toStatus,
            @Param("expectedGeneration") int expectedGeneration,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE NotificationEntity n
        SET n.status = com.notify.common.model.NotificationStatus.QUEUED,
            n.dispatchGeneration = n.dispatchGeneration + 1,
            n.queuedAt = :now,
            n.updatedAt = :now,
            n.version = n.version + 1
        WHERE n.id = :id
          AND n.tenantId = :tenantId
          AND n.status = com.notify.common.model.NotificationStatus.SCHEDULED
          AND n.dispatchGeneration = :expectedGeneration
          AND n.scheduledAt <= :now
    """)
    int releaseScheduledConditional(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("expectedGeneration") int expectedGeneration,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE NotificationEntity n
        SET n.status = com.notify.common.model.NotificationStatus.QUEUED,
            n.dispatchGeneration = n.dispatchGeneration + 1,
            n.nextRetryAt = null,
            n.queuedAt = :now,
            n.updatedAt = :now,
            n.version = n.version + 1
        WHERE n.id = :id
          AND n.tenantId = :tenantId
          AND n.status = com.notify.common.model.NotificationStatus.RETRYING
          AND n.dispatchGeneration = :expectedGeneration
          AND n.nextRetryAt <= :now
    """)
    int releaseRetryConditional(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("expectedGeneration") int expectedGeneration,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE NotificationEntity n
        SET n.status = com.notify.common.model.NotificationStatus.PROCESSING,
            n.updatedAt = :now,
            n.version = n.version + 1
        WHERE n.id = :id
          AND n.tenantId = :tenantId
          AND n.status = com.notify.common.model.NotificationStatus.RETRYING
          AND n.dispatchGeneration = :expectedGeneration
          AND n.nextRetryAt <= :now
    """)
    int claimRetryingToProcessingConditional(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("expectedGeneration") int expectedGeneration,
            @Param("now") Instant now
    );

    // ─── Worker claim (/ ): predicate kept; claim_seq incremented, never refunded ───────────

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE NotificationEntity n
        SET n.status = com.notify.common.model.NotificationStatus.PROCESSING,
            n.attemptCount = n.attemptCount + 1,
            n.claimSeq = n.claimSeq + 1,
            n.processingStartedAt = :now,
            n.firstProcessingAt = COALESCE(n.firstProcessingAt, :now),
            n.nextRetryAt = null,
            n.sender = COALESCE(n.sender, :defaultSender),
            n.updatedAt = :now,
            n.version = n.version + 1
        WHERE n.id = :id
          AND n.tenantId = :tenantId
          AND n.dispatchGeneration = :expectedGeneration
          AND n.status = com.notify.common.model.NotificationStatus.QUEUED
    """)
    int claimQueuedForDelivery(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("expectedGeneration") int expectedGeneration,
            @Param("defaultSender") String defaultSender,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE NotificationEntity n
        SET n.status = com.notify.common.model.NotificationStatus.PROCESSING,
            n.attemptCount = n.attemptCount + 1,
            n.claimSeq = n.claimSeq + 1,
            n.processingStartedAt = :now,
            n.firstProcessingAt = COALESCE(n.firstProcessingAt, :now),
            n.nextRetryAt = null,
            n.sender = COALESCE(n.sender, :defaultSender),
            n.updatedAt = :now,
            n.version = n.version + 1
        WHERE n.id = :id
          AND n.tenantId = :tenantId
          AND n.dispatchGeneration = :expectedGeneration
          AND n.status = com.notify.common.model.NotificationStatus.RETRYING
          AND n.nextRetryAt <= :now
    """)
    int claimDueRetryingForDelivery(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("expectedGeneration") int expectedGeneration,
            @Param("defaultSender") String defaultSender,
            @Param("now") Instant now
    );

    // ─── Worker result writes: all fenced by status=PROCESSING AND generation AND claim_seq () ───────

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE NotificationEntity n
        SET n.status = com.notify.common.model.NotificationStatus.DELIVERED,
            n.provider = :provider,
            n.providerMessageId = :providerMessageId,
            n.completedAt = :now,
            n.updatedAt = :now,
            n.version = n.version + 1
        WHERE n.id = :id
          AND n.tenantId = :tenantId
          AND n.status = com.notify.common.model.NotificationStatus.PROCESSING
          AND n.dispatchGeneration = :expectedGeneration
          AND n.claimSeq = :claimSeq
    """)
    int completeDeliveryConditional(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("expectedGeneration") int expectedGeneration,
            @Param("claimSeq") int claimSeq,
            @Param("provider") String provider,
            @Param("providerMessageId") String providerMessageId,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE NotificationEntity n
        SET n.status = com.notify.common.model.NotificationStatus.FAILED,
            n.lastErrorCode = :errorCode,
            n.completedAt = :now,
            n.nextRetryAt = null,
            n.updatedAt = :now,
            n.version = n.version + 1
        WHERE n.id = :id
          AND n.tenantId = :tenantId
          AND n.status = com.notify.common.model.NotificationStatus.PROCESSING
          AND n.dispatchGeneration = :expectedGeneration
          AND n.claimSeq = :claimSeq
    """)
    int failDeliveryConditional(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("expectedGeneration") int expectedGeneration,
            @Param("claimSeq") int claimSeq,
            @Param("errorCode") String errorCode,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE NotificationEntity n
        SET n.status = com.notify.common.model.NotificationStatus.RETRYING,
            n.nextRetryAt = :nextRetryAt,
            n.lastErrorCode = :errorCode,
            n.updatedAt = :now,
            n.version = n.version + 1
        WHERE n.id = :id
          AND n.tenantId = :tenantId
          AND n.status = com.notify.common.model.NotificationStatus.PROCESSING
          AND n.dispatchGeneration = :expectedGeneration
          AND n.claimSeq = :claimSeq
    """)
    int retryDeliveryConditional(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("expectedGeneration") int expectedGeneration,
            @Param("claimSeq") int claimSeq,
            @Param("nextRetryAt") Instant nextRetryAt,
            @Param("errorCode") String errorCode,
            @Param("now") Instant now
    );

    /** Deferral: refunds the attempt consumed by the claim and counts the deferral. claim_seq is not touched. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE NotificationEntity n
        SET n.status = com.notify.common.model.NotificationStatus.RETRYING,
            n.attemptCount = n.attemptCount - 1,
            n.deferralCount = n.deferralCount + 1,
            n.nextRetryAt = :nextRetryAt,
            n.lastErrorCode = :errorCode,
            n.updatedAt = :now,
            n.version = n.version + 1
        WHERE n.id = :id
          AND n.tenantId = :tenantId
          AND n.status = com.notify.common.model.NotificationStatus.PROCESSING
          AND n.dispatchGeneration = :expectedGeneration
          AND n.claimSeq = :claimSeq
    """)
    int deferDeliveryConditional(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("expectedGeneration") int expectedGeneration,
            @Param("claimSeq") int claimSeq,
            @Param("nextRetryAt") Instant nextRetryAt,
            @Param("errorCode") String errorCode,
            @Param("now") Instant now
    );

    @Query("""
        SELECT new com.notify.notification.dto.NotificationEvidence(
            n.status, n.dispatchGeneration, n.claimSeq, n.deliveryCycle, n.attemptCount,
            n.maxAttempts, n.deferralCount, n.nextRetryAt, n.lastErrorCode
        )
        FROM NotificationEntity n
        WHERE n.id = :id AND n.tenantId = :tenantId
    """)
    Optional<NotificationEvidence> findEvidence(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Query("SELECT n FROM NotificationEntity n WHERE n.status = com.notify.common.model.NotificationStatus.SCHEDULED AND n.scheduledAt <= :cutoff")
    List<NotificationEntity> findOverdueScheduled(@Param("cutoff") Instant cutoff);

    @Query("SELECT n FROM NotificationEntity n WHERE n.status = com.notify.common.model.NotificationStatus.RETRYING AND n.nextRetryAt <= :cutoff")
    List<NotificationEntity> findOverdueRetrying(@Param("cutoff") Instant cutoff);

    @Query("SELECT n FROM NotificationEntity n WHERE n.status = com.notify.common.model.NotificationStatus.SCHEDULED AND n.scheduledAt > :cutoff")
    List<NotificationEntity> findFutureScheduled(@Param("cutoff") Instant cutoff);

    @Query("SELECT n FROM NotificationEntity n WHERE n.status = com.notify.common.model.NotificationStatus.RETRYING AND n.nextRetryAt > :cutoff")
    List<NotificationEntity> findFutureRetrying(@Param("cutoff") Instant cutoff);

    // Keyset cursor queries for bounded overdue scans
    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.status = com.notify.common.model.NotificationStatus.SCHEDULED
          AND n.scheduledAt <= :cutoff
        ORDER BY n.scheduledAt ASC, n.id ASC
    """)
    List<NotificationEntity> findOverdueScheduledFirstBatch(
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );

    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.status = com.notify.common.model.NotificationStatus.SCHEDULED
          AND n.scheduledAt <= :cutoff
          AND (n.scheduledAt > :cursorAt OR (n.scheduledAt = :cursorAt AND n.id > :cursorId))
        ORDER BY n.scheduledAt ASC, n.id ASC
    """)
    List<NotificationEntity> findOverdueScheduledAfterCursor(
            @Param("cutoff") Instant cutoff,
            @Param("cursorAt") Instant cursorAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable
    );

    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.status = com.notify.common.model.NotificationStatus.RETRYING
          AND n.nextRetryAt <= :cutoff
        ORDER BY n.nextRetryAt ASC, n.id ASC
    """)
    List<NotificationEntity> findOverdueRetryingFirstBatch(
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );

    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.status = com.notify.common.model.NotificationStatus.RETRYING
          AND n.nextRetryAt <= :cutoff
          AND (n.nextRetryAt > :cursorAt OR (n.nextRetryAt = :cursorAt AND n.id > :cursorId))
        ORDER BY n.nextRetryAt ASC, n.id ASC
    """)
    List<NotificationEntity> findOverdueRetryingAfterCursor(
            @Param("cutoff") Instant cutoff,
            @Param("cursorAt") Instant cursorAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable
    );

    // ─── / stuck-PROCESSING recovery (, /) ─────────────────────────────────────────────────────

    /**
     * Stuck-PROCESSING scan, keyset-paged on {@code (processing_started_at, id)} like the overdue finders above.
     *
     * <p>Read-only and bounded; it decides nothing. The decision is taken by one of the two conditional updates
     * below, which re-test every predicate including the attempt budget, so a row that changed between the scan and
     * the update simply updates zero rows.
     */
    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.status = com.notify.common.model.NotificationStatus.PROCESSING
          AND n.processingStartedAt <= :cutoff
        ORDER BY n.processingStartedAt ASC, n.id ASC
    """)
    List<NotificationEntity> findStuckProcessingFirstBatch(
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );

    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.status = com.notify.common.model.NotificationStatus.PROCESSING
          AND n.processingStartedAt <= :cutoff
          AND (n.processingStartedAt > :cursorAt OR (n.processingStartedAt = :cursorAt AND n.id > :cursorId))
        ORDER BY n.processingStartedAt ASC, n.id ASC
    """)
    List<NotificationEntity> findStuckProcessingAfterCursor(
            @Param("cutoff") Instant cutoff,
            @Param("cursorAt") Instant cursorAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable
    );

    /**
     * Reap a stuck claim that still has attempt budget: PROCESSING to RETRYING, due immediately.
     *
     * <p>The attempt the lost claim consumed is <b>not</b> refunded (): it may have reached the provider, and
     * refunding it would let an endlessly crashing worker send for ever. {@code attempt_count < max_attempts} is
     * part of this predicate, so the choice between this path and the exhausted one below is atomic with the
     * transition - there is no read-then-write window in which the budget could change.
     *
     * <p>No {@code dispatch_generation} bump (): every result write already requires
     * {@code status = PROCESSING AND dispatch_generation = ? AND claim_seq = ?}, so moving the row out of
     * PROCESSING is what fences the slow original worker. {@code releaseRetryConditional} performs the generation
     * bump later, when the row goes back to QUEUED.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE NotificationEntity n
        SET n.status = com.notify.common.model.NotificationStatus.RETRYING,
            n.nextRetryAt = :now,
            n.lastErrorCode = :errorCode,
            n.updatedAt = :now,
            n.version = n.version + 1
        WHERE n.id = :id
          AND n.tenantId = :tenantId
          AND n.status = com.notify.common.model.NotificationStatus.PROCESSING
          AND n.dispatchGeneration = :expectedGeneration
          AND n.claimSeq = :claimSeq
          AND n.processingStartedAt <= :cutoff
          AND n.attemptCount < n.maxAttempts
    """)
    int reapStuckProcessingToRetrying(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("expectedGeneration") int expectedGeneration,
            @Param("claimSeq") int claimSeq,
            @Param("cutoff") Instant cutoff,
            @Param("errorCode") String errorCode,
            @Param("now") Instant now
    );

    /**
     * Reap a stuck claim whose attempt budget is spent: PROCESSING to FAILED, terminal for this delivery cycle.
     *
     * <p>Without this path an unconditional move to RETRYING would let the due path claim the row again and send
     * past {@code max_attempts}. {@code attempt_count >= max_attempts} is part of the predicate for the same
     * atomicity reason as above. The caller writes the durable dead-letter event in the same transaction; no
     * {@code delivery_attempts} row is written, because no provider outcome was ever observed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE NotificationEntity n
        SET n.status = com.notify.common.model.NotificationStatus.FAILED,
            n.completedAt = :now,
            n.nextRetryAt = null,
            n.lastErrorCode = :errorCode,
            n.updatedAt = :now,
            n.version = n.version + 1
        WHERE n.id = :id
          AND n.tenantId = :tenantId
          AND n.status = com.notify.common.model.NotificationStatus.PROCESSING
          AND n.dispatchGeneration = :expectedGeneration
          AND n.claimSeq = :claimSeq
          AND n.processingStartedAt <= :cutoff
          AND n.attemptCount >= n.maxAttempts
    """)
    int reapStuckProcessingToFailed(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("expectedGeneration") int expectedGeneration,
            @Param("claimSeq") int claimSeq,
            @Param("cutoff") Instant cutoff,
            @Param("errorCode") String errorCode,
            @Param("now") Instant now
    );

    // Prioritized near-due queries
    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.status = com.notify.common.model.NotificationStatus.SCHEDULED
          AND n.scheduledAt > :now AND n.scheduledAt <= :nearDueCutoff
        ORDER BY n.scheduledAt ASC, n.id ASC
    """)
    List<NotificationEntity> findNearDueScheduled(
            @Param("now") Instant now,
            @Param("nearDueCutoff") Instant nearDueCutoff,
            Pageable pageable
    );

    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.status = com.notify.common.model.NotificationStatus.SCHEDULED
          AND n.scheduledAt > :now AND n.scheduledAt <= :nearDueCutoff
          AND (n.scheduledAt > :cursorAt OR (n.scheduledAt = :cursorAt AND n.id > :cursorId))
        ORDER BY n.scheduledAt ASC, n.id ASC
    """)
    List<NotificationEntity> findNearDueScheduledAfterCursor(
            @Param("now") Instant now,
            @Param("nearDueCutoff") Instant nearDueCutoff,
            @Param("cursorAt") Instant cursorAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable
    );

    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.status = com.notify.common.model.NotificationStatus.RETRYING
          AND n.nextRetryAt > :now AND n.nextRetryAt <= :nearDueCutoff
        ORDER BY n.nextRetryAt ASC, n.id ASC
    """)
    List<NotificationEntity> findNearDueRetrying(
            @Param("now") Instant now,
            @Param("nearDueCutoff") Instant nearDueCutoff,
            Pageable pageable
    );

    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.status = com.notify.common.model.NotificationStatus.RETRYING
          AND n.nextRetryAt > :now AND n.nextRetryAt <= :nearDueCutoff
          AND (n.nextRetryAt > :cursorAt OR (n.nextRetryAt = :cursorAt AND n.id > :cursorId))
        ORDER BY n.nextRetryAt ASC, n.id ASC
    """)
    List<NotificationEntity> findNearDueRetryingAfterCursor(
            @Param("now") Instant now,
            @Param("nearDueCutoff") Instant nearDueCutoff,
            @Param("cursorAt") Instant cursorAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable
    );

    // Keyset cursor queries for future scheduled/retrying scans across cycles
    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.status = com.notify.common.model.NotificationStatus.SCHEDULED
          AND n.scheduledAt > :now
        ORDER BY n.scheduledAt ASC, n.id ASC
    """)
    List<NotificationEntity> findFutureScheduledFirstBatch(
            @Param("now") Instant now,
            Pageable pageable
    );

    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.status = com.notify.common.model.NotificationStatus.SCHEDULED
          AND n.scheduledAt > :now
          AND (n.scheduledAt > :cursorAt OR (n.scheduledAt = :cursorAt AND n.id > :cursorId))
        ORDER BY n.scheduledAt ASC, n.id ASC
    """)
    List<NotificationEntity> findFutureScheduledAfterCursor(
            @Param("now") Instant now,
            @Param("cursorAt") Instant cursorAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable
    );

    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.status = com.notify.common.model.NotificationStatus.RETRYING
          AND n.nextRetryAt > :now
        ORDER BY n.nextRetryAt ASC, n.id ASC
    """)
    List<NotificationEntity> findFutureRetryingFirstBatch(
            @Param("now") Instant now,
            Pageable pageable
    );

    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.status = com.notify.common.model.NotificationStatus.RETRYING
          AND n.nextRetryAt > :now
          AND (n.nextRetryAt > :cursorAt OR (n.nextRetryAt = :cursorAt AND n.id > :cursorId))
        ORDER BY n.nextRetryAt ASC, n.id ASC
    """)
    List<NotificationEntity> findFutureRetryingAfterCursor(
            @Param("now") Instant now,
            @Param("cursorAt") Instant cursorAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable
    );

    // ── / (): dead-letter materialization and admin replay ────────────────────────────────────────────

    /**
     * The notification row, locked for the transaction, so a dead-letter materialization is classified on a stable
     * row before any write (, plan 2.2.1). Lock order project-wide: {@code tenants}, {@code notifications},
     * {@code dead_letter_entries}, then append-only writes.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT n FROM NotificationEntity n WHERE n.id = :id")
    Optional<NotificationEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * FAILED to DLQ for one specific cycle and generation (only the authoritative dead-letter event may do
     * this). Every condition is in the predicate, so a duplicate, a stale event, a replayed row and a delivered row
     * all report zero rows.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE NotificationEntity n
        SET n.status = com.notify.common.model.NotificationStatus.DLQ,
            n.updatedAt = :now,
            n.version = n.version + 1
        WHERE n.id = :id
          AND n.tenantId = :tenantId
          AND n.status = com.notify.common.model.NotificationStatus.FAILED
          AND n.deliveryCycle = :eventCycle
          AND n.dispatchGeneration = :eventGeneration
    """)
    int failedToDlqConditional(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("eventCycle") int eventCycle,
            @Param("eventGeneration") int eventGeneration,
            @Param("now") Instant now
    );

    /**
     * Admin replay: DLQ to QUEUED with a new delivery cycle and a new dispatch generation ().
     *
     * <p>Resets only the cycle budgets - {@code attempt_count} and {@code deferral_count} - and clears the
     * per-cycle timestamps. {@code claim_seq} is deliberately untouched: it is a monotonic lifetime fence
     * (), not a cycle budget. {@code max_attempts} and {@code sender} are untouched as well (freezes
     * the send-identity source), and no attempt or history row is ever rewritten.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE NotificationEntity n
        SET n.status = com.notify.common.model.NotificationStatus.QUEUED,
            n.deliveryCycle = n.deliveryCycle + 1,
            n.dispatchGeneration = n.dispatchGeneration + 1,
            n.attemptCount = 0,
            n.deferralCount = 0,
            n.nextRetryAt = null,
            n.processingStartedAt = null,
            n.completedAt = null,
            n.queuedAt = :now,
            n.updatedAt = :now,
            n.version = n.version + 1
        WHERE n.id = :id
          AND n.tenantId = :tenantId
          AND n.status = com.notify.common.model.NotificationStatus.DLQ
          AND n.dispatchGeneration = :expectedGeneration
    """)
    int replayFromDlqConditional(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("expectedGeneration") int expectedGeneration,
            @Param("now") Instant now
    );

    // ─── atomic bulk cancel + history for tenant revocation cascade (, schema L106–107) ─────

    /**
     * Atomic set-based cancel of non-terminal notifications for a revoked tenant, writing the matching
     * {@code notification_status_history} rows in the same statement.
     *
     * <p>A single data-modifying CTE: {@code targets} locks the non-terminal rows {@code FOR UPDATE}
     * (capturing the true {@code from_status}), {@code updated} sets them to CANCELLED, and the final
     * INSERT records one CANCELLED history row per locked notification. Because the rows are locked for
     * the whole statement, a concurrent worker claim ({@code QUEUED/RETRYING -> PROCESSING}) cannot
     * interleave between the state change and the history write, so history can never diverge from the
     * notification lifecycle.
     *
     * <p>Sets status to CANCELLED, cancel_reason to 'TENANT_REVOKED', completed_at = :now; matches schema
     * L106-107. The {@code actor} literal 'SYSTEM' satisfies the {@code Actor} enum CHECK constraint.
     *
     * @return count of notifications cancelled (equals the history rows inserted)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        WITH targets AS (
            SELECT id, tenant_id, status AS from_status
            FROM notifications
            WHERE tenant_id = :tenantId
              AND status IN ('SCHEDULED', 'QUEUED', 'RETRYING')
            FOR UPDATE
        ),
        updated AS (
            UPDATE notifications n
               SET status = 'CANCELLED',
                   cancel_reason = 'TENANT_REVOKED',
                   completed_at = :now,
                   updated_at = :now,
                   version = n.version + 1
              FROM targets t
             WHERE n.id = t.id
        )
        INSERT INTO notification_status_history
            (tenant_id, notification_id, from_status, to_status, reason, actor, occurred_at)
        SELECT t.tenant_id, t.id, t.from_status, 'CANCELLED', 'tenant-revoked side-effect', 'SYSTEM', :now
        FROM targets t
    """, nativeQuery = true)
    int bulkCancelAndRecordForTenantRevocation(@Param("tenantId") UUID tenantId, @Param("now") Instant now);

    /**
     * Find IDs of SCHEDULED notifications for a tenant so their Quartz triggers can be cancelled.
     */
    @Query("SELECT n.id FROM NotificationEntity n WHERE n.tenantId = :tenantId AND n.status = com.notify.common.model.NotificationStatus.SCHEDULED")
    List<UUID> findScheduledIdsByTenantId(@Param("tenantId") UUID tenantId);

    /**
     * Count notifications by tenant for admin aggregate display.
     */
    long countByTenantId(UUID tenantId);

    /** / metrics gauges: global count of notifications in a given status. */
    long countByStatus(NotificationStatus status);

    // ─── business analytics aggregates (D--2 / ; distinct notifications, never attempts) ─────

    /** Status -> count for one tenant over [from, to) anchored on created_at. */
    @Query(value = """
        SELECT status AS status, COUNT(id) AS cnt
        FROM notifications
        WHERE tenant_id = :tenantId AND created_at >= :from AND created_at < :to
        GROUP BY status
    """, nativeQuery = true)
    List<Object[]> statusCountsForTenant(@Param("tenantId") UUID tenantId,
                                         @Param("from") Instant from, @Param("to") Instant to);

    /** (channel, status) -> count for one tenant over [from, to). */
    @Query(value = """
        SELECT channel AS channel, status AS status, COUNT(id) AS cnt
        FROM notifications
        WHERE tenant_id = :tenantId AND created_at >= :from AND created_at < :to
        GROUP BY channel, status
    """, nativeQuery = true)
    List<Object[]> channelStatusCountsForTenant(@Param("tenantId") UUID tenantId,
                                                @Param("from") Instant from, @Param("to") Instant to);

    /** (bucket, status) -> count for one tenant; interval is 'day' or 'hour' (validated by caller). */
    @Query(value = """
        SELECT date_trunc(:interval, created_at) AS bucket, status AS status, COUNT(id) AS cnt
        FROM notifications
        WHERE tenant_id = :tenantId AND created_at >= :from AND created_at < :to
        GROUP BY bucket, status
        ORDER BY bucket
    """, nativeQuery = true)
    List<Object[]> timeseriesForTenant(@Param("tenantId") UUID tenantId,
                                       @Param("from") Instant from, @Param("to") Instant to,
                                       @Param("interval") String interval);

    /** Status -> count across all tenants over [from, to). */
    @Query(value = """
        SELECT status AS status, COUNT(id) AS cnt
        FROM notifications
        WHERE created_at >= :from AND created_at < :to
        GROUP BY status
    """, nativeQuery = true)
    List<Object[]> statusCountsGlobal(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * E2E latency percentiles (ms) over DELIVERED notifications in [from, to), computed from
     * (completed_at - created_at). One notification contributes once; retried attempts never double-count.
     * Returns a single row [p50, p95, p99] as doubles (null when no delivered rows).
     */
    @Query(value = """
        SELECT
            percentile_cont(0.5)  WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (completed_at - created_at)) * 1000) AS p50,
            percentile_cont(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (completed_at - created_at)) * 1000) AS p95,
            percentile_cont(0.99) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (completed_at - created_at)) * 1000) AS p99
        FROM notifications
        WHERE status = 'DELIVERED' AND completed_at IS NOT NULL
          AND created_at >= :from AND created_at < :to
    """, nativeQuery = true)
    List<Object[]> latencyPercentilesGlobal(@Param("from") Instant from, @Param("to") Instant to);

    /** Top tenants by notification volume over [from, to). */
    @Query(value = """
        SELECT tenant_id AS tenantId, COUNT(id) AS volume
        FROM notifications
        WHERE created_at >= :from AND created_at < :to
        GROUP BY tenant_id
        ORDER BY volume DESC
        LIMIT :limit
    """, nativeQuery = true)
    List<Object[]> topTenantsByVolume(@Param("from") Instant from, @Param("to") Instant to,
                                      @Param("limit") int limit);

    /**
     * Top failure reasons over [from, to): one row per failed notification via notifications.last_error_code
     * (never per delivery attempt). FAILED and DLQ terminal statuses with a non-null error code.
     */
    @Query(value = """
        SELECT last_error_code AS reason, COUNT(id) AS cnt
        FROM notifications
        WHERE status IN ('FAILED', 'DLQ') AND last_error_code IS NOT NULL
          AND created_at >= :from AND created_at < :to
        GROUP BY last_error_code
        ORDER BY cnt DESC
        LIMIT :limit
    """, nativeQuery = true)
    List<Object[]> topFailureReasonsGlobal(@Param("from") Instant from, @Param("to") Instant to,
                                           @Param("limit") int limit);

    /** Point-in-time queue depth across all tenants: QUEUED + RETRYING. */
    @Query(value = "SELECT COUNT(id) FROM notifications WHERE status IN ('QUEUED', 'RETRYING')", nativeQuery = true)
    long queueDepthGlobal();

    /** Point-in-time count of notifications currently in DLQ (seam-clean proxy for open DLQ entries). */
    @Query(value = "SELECT COUNT(id) FROM notifications WHERE status = 'DLQ'", nativeQuery = true)
    long dlqCountGlobal();

    // ─── admin cross-tenant notification explorer uses a null-safe Specification (see service) ────────

    /** Newest notifications for the given demo tenants (/ demo feed; caller bounds the page size). */
    List<NotificationEntity> findByTenantIdInOrderByCreatedAtDesc(java.util.Collection<UUID> tenantIds, Pageable pageable);
}
