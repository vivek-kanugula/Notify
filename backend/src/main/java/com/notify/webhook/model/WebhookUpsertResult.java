package com.notify.webhook.model;

/**
 * Outcome of a configuration upsert. {@code secretOnce} is non-null only when this call created the endpoint, because
 * a signing secret is presented exactly once (); {@link #toString()} never prints it.
 */
public record WebhookUpsertResult(WebhookEndpointView view, String secretOnce, boolean created) {

    @Override
    public String toString() {
        return "WebhookUpsertResult[endpointId=" + (view == null ? null : view.id())
                + ", created=" + created + ", secretOnce=" + (secretOnce == null ? "none" : "<redacted>") + "]";
    }
}
