package com.notify.outbox;

import com.notify.outbox.service.OutboxBackoffCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxBackoffCalculatorTest {

    private final Instant baseTime = Instant.parse("2026-10-09T12:00:00Z");

    @Test
    @DisplayName("Attempt 1: base delay 1000ms with jitter +/- 20% in [800ms, 1200ms]")
    void attempt1_boundedJitter() {
        for (int i = 0; i < 50; i++) {
            Instant next = OutboxBackoffCalculator.calculateNextAttemptAt(1, baseTime);
            long delay = Duration.between(baseTime, next).toMillis();
            assertThat(delay).isBetween(800L, 1200L);
        }

        // Exact factor calculations
        Instant minNext = OutboxBackoffCalculator.calculateNextAttemptAt(1, baseTime, 0.80);
        assertThat(Duration.between(baseTime, minNext).toMillis()).isEqualTo(800L);

        Instant maxNext = OutboxBackoffCalculator.calculateNextAttemptAt(1, baseTime, 1.20);
        assertThat(Duration.between(baseTime, maxNext).toMillis()).isEqualTo(1200L);
    }

    @Test
    @DisplayName("Attempt 2: base 2000ms with jitter in [1600ms, 2400ms]")
    void attempt2_boundedJitter() {
        Instant minNext = OutboxBackoffCalculator.calculateNextAttemptAt(2, baseTime, 0.80);
        assertThat(Duration.between(baseTime, minNext).toMillis()).isEqualTo(1600L);

        Instant maxNext = OutboxBackoffCalculator.calculateNextAttemptAt(2, baseTime, 1.20);
        assertThat(Duration.between(baseTime, maxNext).toMillis()).isEqualTo(2400L);
    }

    @Test
    @DisplayName("Attempt 6: base 32000ms with jitter in [25600ms, 38400ms]")
    void attempt6_boundedJitter() {
        Instant minNext = OutboxBackoffCalculator.calculateNextAttemptAt(6, baseTime, 0.80);
        assertThat(Duration.between(baseTime, minNext).toMillis()).isEqualTo(25600L);

        Instant maxNext = OutboxBackoffCalculator.calculateNextAttemptAt(6, baseTime, 1.20);
        assertThat(Duration.between(baseTime, maxNext).toMillis()).isEqualTo(38400L);
    }

    @Test
    @DisplayName("Attempt 7+ and beyond: raw 64000ms clipped at MAX_DELAY_MS 60000ms, jitter in [51200ms, 60000ms]")
    void attempt7AndBeyond_clippedAtCap() {
        // Exponent capped at 6, raw delay = 64,000 ms.
        // At 0.80 factor: 64,000 * 0.8 = 51,200 ms.
        Instant minNext = OutboxBackoffCalculator.calculateNextAttemptAt(7, baseTime, 0.80);
        assertThat(Duration.between(baseTime, minNext).toMillis()).isEqualTo(51200L);

        // At 1.0 factor: 64,000 ms -> strictly clipped to 60,000 ms.
        Instant midNext = OutboxBackoffCalculator.calculateNextAttemptAt(7, baseTime, 1.0);
        assertThat(Duration.between(baseTime, midNext).toMillis()).isEqualTo(60000L);

        // At 1.20 factor: 76,800 ms -> strictly clipped to 60,000 ms.
        Instant maxNext = OutboxBackoffCalculator.calculateNextAttemptAt(7, baseTime, 1.20);
        assertThat(Duration.between(baseTime, maxNext).toMillis()).isEqualTo(60000L);

        // Very high attempts (e.g. 50, 1000) do not cause overflow and stay within [51200ms, 60000ms]
        for (int attempts : new int[]{8, 10, 50, 100, 1000}) {
            for (int i = 0; i < 20; i++) {
                Instant next = OutboxBackoffCalculator.calculateNextAttemptAt(attempts, baseTime);
                long delay = Duration.between(baseTime, next).toMillis();
                assertThat(delay).isBetween(51200L, 60000L);
            }
        }
    }
}
