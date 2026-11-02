package com.notify.dlq.dto;

import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;

/**
 * The notification state **as read now** ().
 *
 * <p>Every field is named {@code current...} on purpose: a quarantined record or an old entry says what was true
 * when it was written, and this says what is true at inspection time. Nothing here is attributed to the record,
 * and no historical cycle is reconstructed - the dispatch envelope never carried one.
 */
public record DlqNotificationSnapshot(
        NotificationStatus currentStatus,
        Channel channel,
        int currentDeliveryCycle,
        int currentDispatchGeneration,
        int currentAttemptCount,
        int maxAttempts
) {
}
