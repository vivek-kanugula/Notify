package com.notify.delivery.provider.webhook;

/**
 * Raw transport result of one webhook attempt, before it is classified (). Keeping transport and classification
 * apart lets {@link WebhookResponseMapper} stay pure and exhaustively unit-tested.
 *
 * @param httpStatus  status code, or {@code null} when no response was received
 * @param bodySnippet response text already bounded to the stored budget <em>including</em> any truncation marker, or
 *                    {@code null}; the sender owns that bound so the value is storable as it stands
 * @param truncated   whether the response body exceeded the read cap or the attempt's remaining budget
 * @param failure     transport failure, {@link Failure#NONE} when a response arrived
 * @param detail      short, non-sensitive description for the attempt row
 * @param latencyMs   wall-clock duration of the attempt
 * @param retryAfter  sanitized {@code Retry-After} header value when the receiver sent one, otherwise {@code null}
 *                    (recorded in the attempt message; it does not shape the backoff in )
 */
public record WebhookSendOutcome(
        Integer httpStatus,
        String bodySnippet,
        boolean truncated,
        Failure failure,
        String detail,
        long latencyMs,
        String retryAfter
) {
    public enum Failure {
        /** A response was received; classification is by status code. */
        NONE,
        /** Connect, response or total budget expired. */
        TIMEOUT,
        /** DNS, connection refused/reset, or an I/O error before a complete response. */
        UNREACHABLE,
        /** TLS handshake or certificate verification failure. */
        TLS,
        /** A malformed response the client could not parse. */
        PROTOCOL,
        /** The destination policy rejected the URL or its resolved addresses. */
        BLOCKED
    }

    public static WebhookSendOutcome response(int status, String snippet, boolean truncated, long latencyMs) {
        return new WebhookSendOutcome(status, snippet, truncated, Failure.NONE, null, latencyMs, null);
    }

    public static WebhookSendOutcome response(
            int status, String snippet, boolean truncated, long latencyMs, String retryAfter) {
        return new WebhookSendOutcome(status, snippet, truncated, Failure.NONE, null, latencyMs, retryAfter);
    }

    public static WebhookSendOutcome failure(Failure failure, String detail, long latencyMs) {
        return new WebhookSendOutcome(null, null, false, failure, detail, latencyMs, null);
    }
}
