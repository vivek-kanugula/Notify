package com.notify.delivery.provider;

/**
 * One outbound plain-text email. {@code idempotencyKey} is {@code <notificationId>-<deliveryCycle>}: stable across
 * retries of the same cycle, new for a DLQ replay. {@code from} is the notification's persisted sender snapshot.
 *
 * <p>Carries recipient and body: {@link #toString()} prints neither.
 */
/**
 * EMAIL provider request boundary.
 *
 * <p>{@code from} is the authoritative RFC 5321 sender email address (persisted on the notification at acceptance).
 * {@code fromName} is the optional RFC 5322 friendly display name (e.g. "Tenant via Notify").
 * {@code replyTo} is the optional reply-to email address directing customer replies to the tenant.
 */
public record EmailSendRequest(
        String idempotencyKey,
        String from,
        String to,
        String subject,
        String text,
        String replyTo,
        String fromName
) {
    public EmailSendRequest(String idempotencyKey, String from, String to, String subject, String text) {
        this(idempotencyKey, from, to, subject, text, null, null);
    }

    public EmailSendRequest(String idempotencyKey, String from, String to, String subject, String text, String replyTo) {
        this(idempotencyKey, from, to, subject, text, replyTo, null);
    }

    @Override
    public String toString() {
        return "EmailSendRequest[idempotencyKey=" + idempotencyKey + "]";
    }
}
