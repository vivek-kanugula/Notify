package com.notify.inapp.service;

import com.notify.common.model.Channel;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.delivery.service.DeliveryResultSideEffect;
import com.notify.notification.dto.ClaimSnapshot;
import org.springframework.stereotype.Component;

/**
 * The IN_APP side effect of a delivery result (/ , ): the inbox row, written inside the checked result
 * transaction and after the fenced status update.
 *
 * <p>It delegates to {@link InAppMessageWriter} and adds nothing, so there is exactly one code path that can insert
 * an {@code inapp_messages} row and exactly one place that takes the ordering lock.
 */
@Component
public class InAppDeliveryPersistence implements DeliveryResultSideEffect {

    private final InAppMessageWriter writer;

    public InAppDeliveryPersistence(InAppMessageWriter writer) {
        this.writer = writer;
    }

    @Override
    public Channel channel() {
        return Channel.IN_APP;
    }

    @Override
    public void afterFencedDelivery(ClaimSnapshot claim, PersistableOutcome.Delivered outcome) {
        writer.write(claim);
    }
}