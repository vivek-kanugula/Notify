package com.notify.delivery.model;

import java.time.Duration;

/**
 * Provider-neutral outcome of one send attempt (TRD name). Produced by {@code EmailProvider} adapters, which never
 * touch the database. Error messages and snippets must already be free of recipient addresses and secrets.
 */
public sealed interface DeliveryResult {

    /** The provider accepted the message (DELIVERED = accepted, ). */
    record Success(String messageId) implements DeliveryResult {
    }

    /**
     * Failed in a way that may succeed later; the remote effect may be unknown (same idempotency key on retry).
     *
     * <p>{@code retryAfterSeconds} carries a receiver-supplied {@code Retry-After} when there was a valid one, and
     * is {@code null} otherwise (/ , decision as adjusted). It is a <b>floor</b> on the next
     * attempt's due time and nothing more: it can only delay a retry, never shorten the exponential backoff, and
     * never extend a notification past its attempt budget. This is still a real attempt - it is counted, it gets its
     * attempt-history row, and it is never refunded. Only a local refusal taken before any provider contact uses the
     * attempt-free {@link Deferred} path.
     */
    record RetryableFailure(String code, String message, Integer httpStatus, String snippet,
                            Integer retryAfterSeconds) implements DeliveryResult {

        /** Without a receiver hint, which is every case except a webhook 429 or 503 that sent one. */
        public RetryableFailure(String code, String message, Integer httpStatus, String snippet) {
            this(code, message, httpStatus, snippet, null);
        }
    }

    /** Failed in a way retrying cannot fix. */
    record PermanentFailure(String code, String message, Integer httpStatus, String snippet) implements DeliveryResult {
    }

    /** Not attempted now; try again after {@code retryAfter} without consuming a retry attempt. */
    record Deferred(String code, Duration retryAfter) implements DeliveryResult {
    }
}
