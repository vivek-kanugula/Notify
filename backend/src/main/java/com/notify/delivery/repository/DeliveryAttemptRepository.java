package com.notify.delivery.repository;

import com.notify.delivery.entity.DeliveryAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttemptEntity, UUID> {

    List<DeliveryAttemptEntity> findByTenantIdAndNotificationIdOrderByAttemptNumberAsc(UUID tenantId, UUID notificationId);
}
