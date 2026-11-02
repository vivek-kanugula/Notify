package com.notify.delivery.retry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential retry delay with jitter. Pure apart from the injected {@link RandomGenerator}.
 * {@code delay(n) = clamp(min(max, base * 2^(n-1)) * (1 + jitter * (2r - 1)), base * (1 - jitter), max)} with r uniform in [0, 1).
 */
@Component
public class ExponentialBackoffCalculator {

    private final RetryProperties properties;
    private final RandomGenerator random;

    @Autowired
    public ExponentialBackoffCalculator(RetryProperties properties) {
        this(properties, RandomGenerator.getDefault());
    }

    public ExponentialBackoffCalculator(RetryProperties properties, RandomGenerator random) {
        this.properties = properties;
        this.random = random;
    }

    /** Delay before the retry that follows failed attempt number {@code attemptNumber} (1-based). */
    public Duration delay(int attemptNumber) {
        int exponent = Math.min(Math.max(attemptNumber, 1) - 1, 30);
        long baseMillis = properties.baseDelay().toMillis();
        long maxMillis = properties.maxDelay().toMillis();

        double raw = (double) baseMillis * (double) (1L << exponent);
        double capped = Math.min((double) maxMillis, raw);
        double factor = 1.0 + properties.jitter() * (2.0 * random.nextDouble() - 1.0);
        double jittered = capped * factor;

        double floor = baseMillis * (1.0 - properties.jitter());
        long millis = (long) Math.max(floor, Math.min((double) maxMillis, jittered));
        return Duration.ofMillis(millis);
    }
}
