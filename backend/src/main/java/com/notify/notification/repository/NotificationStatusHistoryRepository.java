package com.notify.notification.repository;

import com.notify.notification.entity.NotificationStatusHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationStatusHistoryRepository extends JpaRepository<NotificationStatusHistoryEntity, Long> {

    @Query("SELECT h FROM NotificationStatusHistoryEntity h WHERE h.tenantId = :tenantId AND h.notificationId = :notificationId ORDER BY h.occurredAt ASC, h.id ASC")
    List<NotificationStatusHistoryEntity> findByTenantIdAndNotificationIdOrderByOccurredAtAsc(@Param("tenantId") UUID tenantId, @Param("notificationId") UUID notificationId);

    java.util.Optional<NotificationStatusHistoryEntity> findFirstByTenantIdAndNotificationIdOrderByOccurredAtAsc(UUID tenantId, UUID notificationId);

    // the revocation CANCELLED history rows are written atomically with the cancel UPDATE by
    // NotificationRepository#bulkCancelAndRecordForTenantRevocation (single data-modifying CTE).
}
