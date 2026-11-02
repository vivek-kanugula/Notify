package com.notify.delivery.service;

import com.notify.delivery.entity.DeliveryAttemptEntity;
import com.notify.delivery.repository.DeliveryAttemptRepository;
import com.notify.dlq.dto.DlqAttemptView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Reads attempt history and maps it to the safe projection (). */
@Service
public class DefaultDeliveryAttemptReader implements DeliveryAttemptReader {

    private final DeliveryAttemptRepository attempts;

    public DefaultDeliveryAttemptReader(DeliveryAttemptRepository attempts) {
        this.attempts = attempts;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DlqAttemptView> attemptsFor(UUID tenantId, UUID notificationId) {
        return attempts.findByTenantIdAndNotificationIdOrderByAttemptNumberAsc(tenantId, notificationId).stream()
                .sorted(Comparator
                        .comparingInt(DeliveryAttemptEntity::getDeliveryCycle)
                        .thenComparingInt(DeliveryAttemptEntity::getAttemptNumber)
                        .reversed())
                .map(a -> new DlqAttemptView(
                        a.getAttemptNumber(),
                        a.getDeliveryCycle(),
                        a.getOutcome() == null ? null : a.getOutcome().name(),
                        a.getErrorCode(),
                        a.getHttpStatus(),
                        a.getProvider(),
                        a.getLatencyMs(),
                        a.getStartedAt(),
                        a.getFinishedAt()))
                .toList();
    }
}
