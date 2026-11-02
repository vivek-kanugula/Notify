package com.notify.webhook.service;

import java.util.UUID;

/**
 * Sends the single signed ping behind {@code POST /api/v1/webhook-endpoint/test} (). Declared here so the
 * controller depends on the webhook feature's own boundary; the implementation lives with the sender it reuses, so
 * a test ping goes through exactly the same destination policy, signing and transport as a real delivery.
 */
public interface WebhookPingSender {

    /**
     * @param delivered  whether the receiver answered 2xx
     * @param httpStatus status code, or {@code null} when no response arrived
     * @param outcome    short classification for the operator: {@code DELIVERED}, {@code RETRYABLE}, {@code PERMANENT}
     * @param errorCode  the delivery error code when not delivered, otherwise {@code null}
     * @param detail     sanitized explanation; never a response body or a secret
     */
    record PingResult(boolean delivered, Integer httpStatus, String outcome, String errorCode, String detail) {
    }

    PingResult sendTestPing(UUID tenantId);
}
