package com.notify.delivery.provider;

import com.notify.delivery.model.DeliveryResult;

/**
 * Email provider SPI. Implementations must not touch the database and must not throw for provider-side failures:
 * every outcome, including timeouts and malformed responses, is mapped to a {@link DeliveryResult}. The delivery
 * orchestrator calls this outside any database transaction.
 */
public interface EmailProvider {

    /** Stable provider name stored on the notification and attempt rows (at most 64 characters). */
    String name();

    DeliveryResult send(EmailSendRequest request);
}
