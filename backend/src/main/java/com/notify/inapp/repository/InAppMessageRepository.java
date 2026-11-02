package com.notify.inapp.repository;

import com.notify.inapp.entity.InAppMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bounded, tenant-and-recipient scoped access to {@code inapp_messages} (/ ).
 *
 * <p>Every read is keyed by {@code (tenant_id, recipient_ref)} and bounded by a limit, and every ascending read is
 * ordered by {@code seq} - the drain depends on that order, not on insertion time. The earlier unbounded
 * {@code findByTenantIdAndRecipientRefOrderBySeqDesc} is deliberately gone rather than left as a trap.
 */
@Repository
public interface InAppMessageRepository extends JpaRepository<InAppMessageEntity, Long> {

    /** The drain and catch-up query (): ascending, above the cursor, bounded by the caller's limit. */
    @Query(value = """
            SELECT seq, notification_id, subject, body, read_at, created_at
            FROM inapp_messages
            WHERE tenant_id = :tenantId AND recipient_ref = :recipientRef AND seq > :cursor
            ORDER BY seq ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findAscendingAbove(
            @Param("tenantId") UUID tenantId,
            @Param("recipientRef") String recipientRef,
            @Param("cursor") long cursor,
            @Param("limit") int limit);

    /** Keyset page for the inbox UI (): newest first, strictly below the supplied cursor when given. */
    @Query(value = """
            SELECT seq, notification_id, subject, body, read_at, created_at
            FROM inapp_messages
            WHERE tenant_id = :tenantId AND recipient_ref = :recipientRef AND seq < :before
            ORDER BY seq DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findDescendingBelow(
            @Param("tenantId") UUID tenantId,
            @Param("recipientRef") String recipientRef,
            @Param("before") long before,
            @Param("limit") int limit);

    @Query(value = """
            SELECT count(*) FROM inapp_messages
            WHERE tenant_id = :tenantId AND recipient_ref = :recipientRef AND read_at IS NULL
            """, nativeQuery = true)
    long countUnread(@Param("tenantId") UUID tenantId, @Param("recipientRef") String recipientRef);

    /**
     * Marks one message read, scoped by tenant and recipient so a cross-tenant id updates nothing (the caller then
     * answers 404). Idempotent: an already-read row keeps its first {@code read_at}.
     */
    @Modifying
    @Query(value = """
            UPDATE inapp_messages SET read_at = :readAt
            WHERE seq = :seq AND tenant_id = :tenantId AND recipient_ref = :recipientRef AND read_at IS NULL
            """, nativeQuery = true)
    int markRead(@Param("tenantId") UUID tenantId,
                 @Param("recipientRef") String recipientRef,
                 @Param("seq") long seq,
                 @Param("readAt") Instant readAt);

    @Query(value = """
            SELECT count(*) FROM inapp_messages
            WHERE seq = :seq AND tenant_id = :tenantId AND recipient_ref = :recipientRef
            """, nativeQuery = true)
    long existsForRecipient(@Param("tenantId") UUID tenantId,
                            @Param("recipientRef") String recipientRef,
                            @Param("seq") long seq);
}