package com.notify.delivery.provider.webhook;

import java.time.Duration;

/**
 * One wall-clock budget for a whole webhook attempt ("5 s total per attempt, connect ≤ 2 s inside it").
 *
 * <p>It is created before name resolution and carried through pool acquisition, connection setup, TLS, the response
 * headers, the bounded body read and the cleanup, so every phase spends the <em>same</em> budget instead of each phase
 * holding its own independent timeout. Per-phase timeouts are derived from what is left, never from the configured
 * value alone, which is what keeps the worst case at the budget rather than at the sum of the phases.
 *
 * <p>Monotonic by construction ({@link System#nanoTime()}): a clock adjustment cannot extend or collapse an attempt.
 * The injected business {@code Clock} is deliberately not used here — this is a duration measurement, not a business
 * time decision.
 */
public final class WebhookDeadline {

    /** Never derive a zero or negative timeout: a 1 ms floor keeps the HTTP client's own validation happy. */
    private static final long MIN_TIMEOUT_MS = 1;

    private final long startedAtNanos;
    private final long budgetNanos;

    private WebhookDeadline(long startedAtNanos, long budgetNanos) {
        this.startedAtNanos = startedAtNanos;
        this.budgetNanos = budgetNanos;
    }

    public static WebhookDeadline startingNow(Duration budget) {
        return new WebhookDeadline(System.nanoTime(), Math.max(0L, budget.toNanos()));
    }

    /** For tests: a budget that started a known time ago. */
    static WebhookDeadline startedAgo(Duration budget, Duration elapsed) {
        return new WebhookDeadline(System.nanoTime() - elapsed.toNanos(), Math.max(0L, budget.toNanos()));
    }

    public long elapsedMs() {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    public long remainingMs() {
        long remaining = (startedAtNanos + budgetNanos - System.nanoTime()) / 1_000_000L;
        return Math.max(0L, remaining);
    }

    public boolean expired() {
        return System.nanoTime() >= startedAtNanos + budgetNanos;
    }

    /** Absolute nanoTime instant the attempt must be finished by; used by the bounded body reader. */
    public long deadlineNanos() {
        return startedAtNanos + budgetNanos;
    }

    /**
     * Timeout to allow one phase: the configured value, but never more than the budget still has.
     *
     * @param configuredMs the phase's own configured ceiling
     */
    public long phaseTimeoutMs(long configuredMs) {
        return Math.max(MIN_TIMEOUT_MS, Math.min(configuredMs, remainingMs()));
    }

    /**
     * Connect timeout for one TCP attempt when the destination resolved to {@code addressCount} addresses.
     *
     * <p>Apache HttpClient tries the addresses in turn, so a single configured connect timeout would let a destination
     * with several blackholed addresses spend {@code addressCount × connect} before the response phase even starts.
     * Dividing the connect share keeps the sum of the attempts inside the budget.
     */
    public long connectTimeoutMs(long configuredConnectMs, int addressCount) {
        long share = configuredConnectMs / Math.max(1, addressCount);
        return phaseTimeoutMs(Math.max(MIN_TIMEOUT_MS, share));
    }
}
