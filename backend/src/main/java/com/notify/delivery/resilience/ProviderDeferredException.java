package com.notify.delivery.resilience;

import java.time.Duration;

/**
 * A local resilience refusal, raised <b>before any provider contact</b> (/ , ).
 *
 * <p>A plain {@link RuntimeException} on purpose, and deliberately <b>not</b> a delivery failure: the adapter
 * catches it at the provider-call site and turns it into {@code DeliveryResult.Deferred}, which refunds the claim's
 * attempt and counts a deferral. It is the mirror image of {@code ChannelPreparationException}, which signals an
 * infrastructure failure before a provider request and is rethrown for the worker's recovery policy. Both exist so
 * that "nothing was sent" is never mistaken for "the provider failed".
 *
 * <p>This is the only path allowed to be attempt-free. A real request whose receiver answered - a {@code 429}
 * included - is an attempt, keeps its attempt-history row, and is never refunded (as adjusted).
 */
public class ProviderDeferredException extends RuntimeException {

    private final String code;
    private final Duration retryAfter;

    public ProviderDeferredException(String code, Duration retryAfter) {
        // No message beyond the code: this is never a provider payload and must not carry tenant data.
        super(code);
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public String code() {
        return code;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        // Control flow, not a fault: the stack trace is never read and this is on the hot path of every refusal.
        return this;
    }
}
