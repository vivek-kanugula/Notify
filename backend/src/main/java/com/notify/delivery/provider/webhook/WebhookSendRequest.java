package com.notify.delivery.provider.webhook;

import com.notify.webhook.security.DestinationResolver.PinnedDestination;

import java.util.Map;

/**
 * One outbound webhook request, fully prepared: a cleared destination, the exact bytes to send, and the headers that
 * accompany them. {@link #toString()} prints identifiers only — the body, the signature and the URL stay out of logs.
 */
public record WebhookSendRequest(
        PinnedDestination destination,
        String deliveryId,
        byte[] body,
        Map<String, String> headers
) {
    public WebhookSendRequest {
        headers = Map.copyOf(headers);
    }

    @Override
    public String toString() {
        return "WebhookSendRequest[deliveryId=" + deliveryId + ", bodyBytes=" + (body == null ? 0 : body.length)
                + ", host=" + (destination == null ? null : destination.url().host()) + "]";
    }
}
