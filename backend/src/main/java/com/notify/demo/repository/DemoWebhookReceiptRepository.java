package com.notify.demo.repository;

import com.notify.demo.entity.DemoWebhookReceiptEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Receipts stored by the demo webhook receiver (). Demo surface only; nothing here feeds delivery. */
public interface DemoWebhookReceiptRepository extends JpaRepository<DemoWebhookReceiptEntity, UUID> {

    List<DemoWebhookReceiptEntity> findByTenantIdOrderByReceivedAtDesc(UUID tenantId);

    List<DemoWebhookReceiptEntity> findByNotificationIdOrderByReceivedAtAsc(UUID notificationId);

    long countByNotificationId(UUID notificationId);
}
