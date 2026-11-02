package com.notify.delivery.provider.webhook;

import com.notify.delivery.model.DeliveryResult;

/**
 * Pure mapping from a webhook transport outcome to a {@link DeliveryResult} (, refining TRD section 10).
 *
 * <p>Retryable: 408, 429, 5xx, timeout, unreachable, TLS failure. Permanent: 3xx (never followed), every other 4xx,
 * an unparsable response, and a destination the policy blocked. A 2xx is success regardless of the body, including an
 * oversize or unreadable one — unlike the email provider, nothing in the body is needed to confirm acceptance.
 *
 * <p>The body snippet is passed through exactly as the sender produced it: the sender owns the stored budget and the
 * truncation marker, so the value this mapper puts on a result is already storable (, F6).
 */
public final class WebhookResponseMapper {

    public static final String PROVIDER_NAME = "webhook";

    private WebhookResponseMapper() {
    }

    public static DeliveryResult map(WebhookSendOutcome outcome) {
        if (outcome.failure() != WebhookSendOutcome.Failure.NONE) {
            return switch (outcome.failure()) {
                case TIMEOUT -> retryable("WEBHOOK_TIMEOUT", detail(outcome, "Receiver did not answer in time"), outcome);
                case UNREACHABLE -> retryable("WEBHOOK_UNREACHABLE", detail(outcome, "Receiver could not be reached"), outcome);
                case TLS -> retryable("WEBHOOK_TLS_FAILURE", detail(outcome, "TLS handshake with the receiver failed"), outcome);
                case PROTOCOL -> permanent("WEBHOOK_PROTOCOL_ERROR", detail(outcome, "Receiver sent an unparsable response"), outcome);
                case BLOCKED -> permanent("WEBHOOK_DESTINATION_BLOCKED", detail(outcome, "Destination is not allowed"), outcome);
                case NONE -> throw new IllegalStateException("unreachable");
            };
        }

        int status = outcome.httpStatus() == null ? 0 : outcome.httpStatus();
        if (status >= 200 && status < 300) {
            // DELIVERED for WEBHOOK = a 2xx within the timeout (). A webhook receiver returns no message id.
            return new DeliveryResult.Success(null);
        }
        if (status >= 300 && status < 400) {
            return permanent("WEBHOOK_REDIRECT_NOT_ALLOWED",
                    "Receiver answered " + status + "; redirects are never followed", outcome);
        }
        if (status == 408) {
            return retryable("WEBHOOK_TIMEOUT", "Receiver answered 408", outcome);
        }
        if (status == 429) {
            return retryableWithFloor("WEBHOOK_RATE_LIMITED", "Receiver answered 429", outcome);
        }
        if (status == 503) {
            return retryableWithFloor("WEBHOOK_SERVER_ERROR", "Receiver answered 503", outcome);
        }
        if (status >= 500 && status < 600) {
            return retryable("WEBHOOK_SERVER_ERROR", "Receiver answered " + status, outcome);
        }
        if (status >= 400 && status < 500) {
            return permanent("WEBHOOK_CLIENT_ERROR", "Receiver answered " + status, outcome);
        }
        return permanent("WEBHOOK_PROTOCOL_ERROR", "Receiver answered an unexpected status " + status, outcome);
    }

    /**
     * A retryable outcome that carries <b>no</b> scheduling floor: the backoff policy alone decides when the next
     * attempt happens. Everything retryable except {@code 429} and {@code 503} lands here - a {@code 408}, any
     * other {@code 5xx}, and every transport failure.
     */
    private static DeliveryResult retryable(String code, String message, WebhookSendOutcome outcome) {
        return new DeliveryResult.RetryableFailure(code, withRetryAfter(message, outcome), outcome.httpStatus(),
                outcome.bodySnippet(), null);
    }

    /** A retryable outcome on one of the two statuses covers, so a valid {@code Retry-After} is a floor. */
    private static DeliveryResult retryableWithFloor(String code, String message, WebhookSendOutcome outcome) {
        return new DeliveryResult.RetryableFailure(code, withRetryAfter(message, outcome), outcome.httpStatus(),
                outcome.bodySnippet(), retryAfterSecondsFloor(outcome));
    }

    /**
     * A receiver-supplied {@code Retry-After}, as a floor for the next attempt (/ , decision ).
     *
     * <p><b>Called only for an actual {@code 429} or {@code 503} response</b>, which is the scope accepts and
     * nothing wider. A {@code 408}, any other {@code 5xx} and every transport failure get no floor at all: their
     * header, if there is one, is still recorded verbatim by {@link #withRetryAfter} but never acted on. An earlier
     * version applied the floor to every retryable outcome, which honoured statuses the decision does not cover.
     *
     * <p>Only the <b>delta-seconds</b> form is honoured. The HTTP-date form is ignored deliberately: it invites
     * clock-skew bugs for no benefit here, and the raw header value is already in the attempt message.
     *
     * <p>It is never read from a 2xx and never used to shorten a backoff: the orchestrator takes the larger of its
     * own exponential delay and this floor, and clamps the result. And this remains a real attempt: it is counted
     * and recorded, never refunded.
     */
    private static Integer retryAfterSecondsFloor(WebhookSendOutcome outcome) {
        String raw = outcome.retryAfter();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(raw.trim());
            return seconds > 0 && seconds <= Integer.MAX_VALUE ? (int) seconds : null;
        } catch (NumberFormatException e) {
            return null;                 // an HTTP-date or anything unparseable: recorded, not acted on
        }
    }

    private static DeliveryResult permanent(String code, String message, WebhookSendOutcome outcome) {
        return new DeliveryResult.PermanentFailure(code, withRetryAfter(message, outcome), outcome.httpStatus(),
                outcome.bodySnippet());
    }

    /**
     * a {@code Retry-After} the receiver sent is recorded in the attempt message. The value arrives already
     * sanitized and length-capped from the sender.
     *
     * <p>As of / () the delta-seconds form <em>also</em> shapes scheduling on a {@code 429} or a
     * {@code 503}, and only as a floor on the next attempt's due time - see {@link #retryAfterSecondsFloor}. On
     * every other status, and on a transport failure, this message is the only thing the header affects. It remains
     * the verbatim record for an operator, including for the HTTP-date form, which is never acted on.
     */
    private static String withRetryAfter(String message, WebhookSendOutcome outcome) {
        return outcome.retryAfter() == null ? message : message + " (Retry-After: " + outcome.retryAfter() + ")";
    }

    private static String detail(WebhookSendOutcome outcome, String fallback) {
        return outcome.detail() == null || outcome.detail().isBlank() ? fallback : outcome.detail();
    }
}
