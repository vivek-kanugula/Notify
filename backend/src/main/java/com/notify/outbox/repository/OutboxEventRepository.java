package com.notify.outbox.repository;

import com.notify.common.model.OutboxStatus;
import com.notify.outbox.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    List<OutboxEventEntity> findByTenantIdAndStatus(UUID tenantId, OutboxStatus status);

    /** / metrics gauges: pending backlog and the age of the oldest pending event. */
    long countByStatus(OutboxStatus status);

    @Query(value = "SELECT MIN(created_at) FROM outbox_events WHERE status = 'PENDING'", nativeQuery = true)
    Instant oldestPendingCreatedAt();

    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' AND attempts = 0 ORDER BY created_at ASC, id ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEventEntity> findPendingFreshForUpdateSkipLocked(@Param("limit") int limit);

    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' AND attempts = 0 AND id NOT IN (:excludedIds) ORDER BY created_at ASC, id ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEventEntity> findPendingFreshExcludingIdsForUpdateSkipLocked(@Param("excludedIds") List<UUID> excludedIds, @Param("limit") int limit);

    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' AND attempts > 0 AND next_attempt_at <= :now ORDER BY next_attempt_at ASC, created_at ASC, id ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEventEntity> findPendingRetriesForUpdateSkipLocked(@Param("now") Instant now, @Param("limit") int limit);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboxEventEntity e SET e.status = :status, e.publishedAt = :publishedAt WHERE e.id = :id")
    int updateStatusAndPublishedAt(@Param("id") UUID id, @Param("status") OutboxStatus status, @Param("publishedAt") Instant publishedAt);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboxEventEntity e SET e.attempts = e.attempts + 1, e.nextAttemptAt = :nextAttemptAt WHERE e.id = :id")
    int recordFailure(@Param("id") UUID id, @Param("nextAttemptAt") Instant nextAttemptAt);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM OutboxEventEntity e WHERE e.status = com.notify.common.model.OutboxStatus.PUBLISHED AND e.publishedAt < :cutoff")
    int purgePublishedBefore(@Param("cutoff") Instant cutoff);
}
