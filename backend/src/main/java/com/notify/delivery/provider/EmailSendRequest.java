package com.notify.delivery.provider;

/**
 * One outbound plain-text email. {@code idempotencyKey} is {@code <notificationId>-<deliveryCycle>}: stable across
 * retries of the same cycle, new for a DLQ replay. {@code from} is the notification's persisted sender snapshot.
 *
 * <p>Carries recipient and body: {@link #toString()} prints neither.
 */
public record EmailSendRequest(
        String idempotencyKey,
        String from,
        String to,
        String subject,
        String text,
        String replyTo
) {
    public EmailSendRequest(String idempotencyKey, String from, String to, String subject, String text) {
        this(idempotencyKey, from, to, subject, text, null);
    }

    @Override
    public String toString() {
        return "EmailSendRequest[idempotencyKey=" + idempotencyKey + "]";
    }
}
