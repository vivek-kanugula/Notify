package com.notify.delivery.service;

import com.notify.delivery.repository.DeliveryAttemptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Default {@link DeliveryQueryService}: the only class that touches {@link DeliveryAttemptRepository} for
 * admin reads. Returns attempts ordered by attempt number for the notification detail timeline.
 */
@Service
public class DeliveryQueryServiceImpl implements DeliveryQueryService {

    private final DeliveryAttemptRepository deliveryAttemptRepository;

    public DeliveryQueryServiceImpl(DeliveryAttemptRepository deliveryAttemptRepository) {
        this.deliveryAttemptRepository = deliveryAttemptRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttemptView> attemptsForNotification(UUID tenantId, UUID notificationId) {
        return deliveryAttemptRepository
                .findByTenantIdAndNotificationIdOrderByAttemptNumberAsc(tenantId, notificationId).stream()
                .map(a -> new AttemptView(
                        a.getAttemptNumber(), a.getDeliveryCycle(), a.getProvider(),
                        a.getStartedAt(), a.getFinishedAt(), a.getOutcome(),
                        a.getErrorCode(), a.getHttpStatus(), a.getLatencyMs()))
                .toList();
    }
}
