package com.notify.outbox.service;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bounded exponential backoff calculator with jitter for outbox relay retries.
 * - Base delay: 1,000 ms
 * - Cap: 60,000 ms
 * - Shift exponent: capped at 6 (max raw delay 64,000 ms) preventing bitshift overflow
 * - Jitter range: [0.80, 1.20] (uniform +/- 20%)
 * - Final delay strictly clipped at MAX_DELAY_MS
 */
public final class OutboxBackoffCalculator {

    public static final long BASE_DELAY_MS = 1000L;
    public static final long MAX_DELAY_MS = 60_000L;
    public static final double JITTER_MIN = 0.80; // -20%
    public static final double JITTER_MAX = 1.20; // +20%

    private OutboxBackoffCalculator() {
    }

    public static Instant calculateNextAttemptAt(int attempts, Instant now) {
        double factor = ThreadLocalRandom.current().nextDouble(JITTER_MIN, JITTER_MAX);
        return calculateNextAttemptAt(attempts, now, factor);
    }

    public static Instant calculateNextAttemptAt(int attempts, Instant now, double jitterFactor) {
        int exponent = Math.min(Math.max(0, attempts - 1), 6);
        long rawDelayMs = BASE_DELAY_MS * (1L << exponent);
        double factor = Math.max(JITTER_MIN, Math.min(JITTER_MAX, jitterFactor));
        long jitteredDelayMs = (long) (rawDelayMs * factor);
        long finalDelayMs = Math.min(jitteredDelayMs, MAX_DELAY_MS);
        return now.plusMillis(finalDelayMs);
    }
}
