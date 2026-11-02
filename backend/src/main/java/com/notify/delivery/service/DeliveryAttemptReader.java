package com.notify.delivery.service;

import com.notify.dlq.dto.DlqAttemptView;

import java.util.List;
import java.util.UUID;

/**
 * The delivery feature read seam for attempt history (/ , ).
 *
 * <p>Exists so no other feature reaches into {@code DeliveryAttemptRepository} or
 * {@code DeliveryAttemptEntity}: features call each other through service interfaces, and this one returns DTOs
 * only. The projection deliberately omits {@code error_message} and {@code response_snippet} - both can contain a
 * receiver body excerpt ().
 */
public interface DeliveryAttemptReader {

    /** Every attempt of every delivery cycle, newest cycle and attempt first. Old cycles are never rewritten. */
    List<DlqAttemptView> attemptsFor(UUID tenantId, UUID notificationId);
}
