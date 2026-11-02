package com.notify.delivery.provider.inapp;

import com.notify.common.model.Channel;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.provider.DeliveryChannelAdapter;
import com.notify.notification.dto.ClaimSnapshot;
import org.springframework.stereotype.Component;

/**
 * IN_APP side of the channel seam (/ , ). There is no provider to call: an in-app delivery is a durable
 * write, and that write belongs to the checked result transaction, not here.
 *
 * <p><b>This adapter is deliberately pure: no I/O, no database access, no clock.</b> That is the whole reason the
 * in-app path cannot repeat the defect repaired - with nothing here that can fail for infrastructure
 * reasons, {@code DeliveryOrchestrator}'s generic catch can never turn a database failure into
 * {@code PROVIDER_UNAVAILABLE} and spend an attempt of the delivery budget.
 *
 * <p>It returns {@code Success} with the notification id as the message id. DELIVERED therefore means "persisted and
 * committed" and nothing more (): the row is written by {@code InAppDeliveryPersistence} inside the same
 * transaction as the fenced status update, and if that write does not happen the whole delivery rolls back
 * (condition C1).
 */
@Component
public class InAppChannelAdapter implements DeliveryChannelAdapter {

    public static final String PROVIDER_NAME = "inapp";

    @Override
    public Channel channel() {
        return Channel.IN_APP;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public DeliveryResult send(ClaimSnapshot claim) {
        return new DeliveryResult.Success(claim.id().toString());
    }
}