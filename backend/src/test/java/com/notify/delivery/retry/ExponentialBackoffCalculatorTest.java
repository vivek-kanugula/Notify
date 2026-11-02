package com.notify.delivery.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R4: backoff calculator, jitter, caps. */
class ExponentialBackoffCalculatorTest {

    /** A Random whose nextDouble() returns a fixed value, giving a fully deterministic jitter factor. */
    private static Random fixed(double value) {
        return new Random() {
            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    private static final RetryProperties DEFAULTS = new RetryProperties(Duration.ofSeconds(30), Duration.ofMinutes(15), 0.2);

    @Test
    @DisplayName("R4 without jitter effect the delay doubles from the base and is capped at the maximum")
    void doublesThenCaps() {
        ExponentialBackoffCalculator calc = new ExponentialBackoffCalculator(DEFAULTS, fixed(0.5)); // factor 1.0
        assertThat(calc.delay(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(calc.delay(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(calc.delay(3)).isEqualTo(Duration.ofSeconds(120));
        assertThat(calc.delay(4)).isEqualTo(Duration.ofSeconds(240));
        assertThat(calc.delay(5)).isEqualTo(Duration.ofSeconds(480));
        assertThat(calc.delay(6)).isEqualTo(Duration.ofMinutes(15));
        assertThat(calc.delay(7)).isEqualTo(Duration.ofMinutes(15));
        assertThat(calc.delay(1_000)).as("no overflow for large attempt numbers").isEqualTo(Duration.ofMinutes(15));
        assertThat(calc.delay(0)).as("attempt numbers below 1 behave like 1").isEqualTo(Duration.ofSeconds(30));
        assertThat(calc.delay(-5)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("R4 jitter spans +/- 20 percent of the capped delay")
    void jitterBounds() {
        Duration low = new ExponentialBackoffCalculator(DEFAULTS, fixed(0.0)).delay(2);        // 60 s * 0.8
        Duration high = new ExponentialBackoffCalculator(DEFAULTS, fixed(0.999999999)).delay(2); // 60 s * ~1.2
        assertThat(low).isEqualTo(Duration.ofSeconds(48));
        assertThat(high.toMillis()).isBetween(71_990L, 72_000L);
    }

    @Test
    @DisplayName("R4 the result never exceeds the maximum even when jitter would push the capped value above it")
    void neverExceedsMax() {
        ExponentialBackoffCalculator calc = new ExponentialBackoffCalculator(DEFAULTS, fixed(0.999999999));
        assertThat(calc.delay(6)).isEqualTo(Duration.ofMinutes(15));
        assertThat(calc.delay(20)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("R4 over many random samples every delay stays within [base*(1-jitter), max]")
    void randomSamplesStayInRange() {
        ExponentialBackoffCalculator calc = new ExponentialBackoffCalculator(DEFAULTS, new Random(42));
        for (int attempt = 1; attempt <= 12; attempt++) {
            for (int i = 0; i < 200; i++) {
                long ms = calc.delay(attempt).toMillis();
                assertThat(ms).isBetween(24_000L, 900_000L);
            }
        }
    }

    @Test
    @DisplayName("R4 demo settings (3 s / 60 s) are honored")
    void demoSettings() {
        RetryProperties demo = new RetryProperties(Duration.ofSeconds(3), Duration.ofSeconds(60), 0.2);
        ExponentialBackoffCalculator calc = new ExponentialBackoffCalculator(demo, fixed(0.5));
        assertThat(calc.delay(1)).isEqualTo(Duration.ofSeconds(3));
        assertThat(calc.delay(2)).isEqualTo(Duration.ofSeconds(6));
        assertThat(calc.delay(6)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("R4 invalid retry properties are rejected")
    void rejectsInvalidProperties() {
        assertThatThrownBy(() -> new RetryProperties(Duration.ZERO, Duration.ofMinutes(1), 0.2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryProperties(Duration.ofMinutes(2), Duration.ofMinutes(1), 0.2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryProperties(Duration.ofSeconds(1), Duration.ofMinutes(1), 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryProperties(Duration.ofSeconds(1), Duration.ofMinutes(1), -0.1)).isInstanceOf(IllegalArgumentException.class);
    }
}
