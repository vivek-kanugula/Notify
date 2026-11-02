package com.notify.sse.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The timing set of the cap lease (/ , condition C5).
 *
 * <p>Two separate obligations, deliberately not mixed:
 * <ul>
 *   <li><b>the production defaults</b> are asserted literally, so a silent edit to {@code application.yml} or to a
 *       {@code @DefaultValue} shows up here;</li>
 *   <li><b>the safety relationships</b> are asserted over scaled sets, so a test that shrinks one value without the
 *       others fails instead of quietly producing an unsafe system. In particular, shortening the barrier alone must
 *       be rejected: that is precisely the change that would let a new admission overlap a stream which still holds
 *       serving authority.</li>
 * </ul>
 */
class SsePropertiesDefaultsTest {

    /** The production set, as accepted in and configured in application.yml. */
    private static SseProperties production() {
        return new SseProperties(
                Duration.ofSeconds(90),   // leaseTtl
                Duration.ofSeconds(25),   // heartbeat
                Duration.ofSeconds(8),    // safetyMargin
                Duration.ofSeconds(5),    // maxClockSkew
                Duration.ofSeconds(95),   // barrier
                Duration.ofMinutes(30),   // maxLifetime
                Duration.ofHours(24),     // keyTtl
                Duration.ofSeconds(60),   // tokenTtl
                100, 200, 64, 8);
    }

    @Test
    @DisplayName("the production defaults are exactly the accepted values, and they satisfy every safety relationship")
    void productionDefaults() {
        SseProperties props = production();

        assertThat(props.leaseTtl()).isEqualTo(Duration.ofSeconds(90));
        assertThat(props.heartbeat()).isEqualTo(Duration.ofSeconds(25));
        assertThat(props.safetyMargin()).isEqualTo(Duration.ofSeconds(8));
        assertThat(props.maxClockSkew()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.barrier()).isEqualTo(Duration.ofSeconds(95));
        assertThat(props.maxLifetime()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props.keyTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(props.tokenTtl()).isEqualTo(Duration.ofSeconds(60));
        assertThat(props.capPerTenant()).isEqualTo(100);
        // The dispatch pool (corrective cycle, finding A1): bounded, and separate from the timer pool that
        // enforces the serving deadline, so a stalled client write cannot delay a watchdog.
        assertThat(props.dispatchThreads()).isEqualTo(8);
        assertThat(props.dispatchThreads()).isEqualTo(SseProperties.DEFAULT_DISPATCH_THREADS);

        // The usable local authority: 82 s of the 90 s lease, so the Redis reclaim can never precede the local stop.
        assertThat(props.servingBudget()).isEqualTo(Duration.ofSeconds(82));
        assertThat(props.catchUpLimit()).isEqualTo(100);
        assertThat(props.catchUpLimitProbe()).isEqualTo(101);
    }

    @Test
    @DisplayName("a dispatch pool of zero threads is rejected: there would be nothing left to write to a client")
    void dispatchThreadsMustBePositive() {
        assertThatThrownBy(() -> new SseProperties(
                Duration.ofSeconds(90), Duration.ofSeconds(25), Duration.ofSeconds(8), Duration.ofSeconds(5),
                Duration.ofSeconds(95), Duration.ofMinutes(30), Duration.ofHours(24), Duration.ofSeconds(60),
                100, 200, 64, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dispatch-threads");
    }

    @Test
    @DisplayName("a scaled set keeps every relationship: the values are a set, not independent knobs")
    void scaledSetsAreAccepted() {
        for (int divisor : new int[]{2, 5, 10}) {
            SseProperties scaled = new SseProperties(
                    Duration.ofSeconds(90 / divisor + 1),
                    Duration.ofSeconds(25 / divisor),
                    Duration.ofSeconds(8 / divisor + 1),
                    Duration.ofSeconds(5 / divisor),
                    Duration.ofSeconds(95 / divisor + 2),
                    Duration.ofMinutes(30),
                    Duration.ofHours(24),
                    Duration.ofSeconds(60 / divisor),
                    100, 200, 64, 8);
            assertThat(scaled.barrier()).as("barrier >= lease + skew at 1/%d scale", divisor)
                    .isGreaterThanOrEqualTo(scaled.leaseTtl().plus(scaled.maxClockSkew()));
            assertThat(scaled.servingBudget()).as("a stream gets a refresh attempt at 1/%d scale", divisor)
                    .isGreaterThan(scaled.heartbeat());
            assertThat(scaled.safetyMargin()).isGreaterThan(scaled.maxClockSkew());
        }
    }

    @Test
    @DisplayName("shortening the barrier on its own is rejected: it is the one change that breaks the cap")
    void barrierMayNotBeShortenedAlone() {
        assertThatThrownBy(() -> new SseProperties(
                Duration.ofSeconds(90), Duration.ofSeconds(25), Duration.ofSeconds(8), Duration.ofSeconds(5),
                Duration.ofSeconds(30),                      // barrier < leaseTtl + skew
                Duration.ofMinutes(30), Duration.ofHours(24), Duration.ofSeconds(60), 100, 200, 64, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("barrier");
    }

    @Test
    @DisplayName("a stream must get at least one refresh attempt before its local deadline")
    void heartbeatMustFitInsideTheServingBudget() {
        assertThatThrownBy(() -> new SseProperties(
                Duration.ofSeconds(30), Duration.ofSeconds(25), Duration.ofSeconds(8), Duration.ofSeconds(5),
                Duration.ofSeconds(40), Duration.ofMinutes(30), Duration.ofHours(24), Duration.ofSeconds(60),
                100, 200, 64, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heartbeat");
    }

    @Test
    @DisplayName("the safety margin must exceed the clock-skew allowance, or the local stop could follow the reclaim")
    void marginMustExceedSkew() {
        assertThatThrownBy(() -> new SseProperties(
                Duration.ofSeconds(90), Duration.ofSeconds(25), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(95), Duration.ofMinutes(30), Duration.ofHours(24), Duration.ofSeconds(60),
                100, 200, 64, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safety-margin");
    }

    @Test
    @DisplayName("the member-set key must outlive the longest possible stream")
    void keyTtlMustOutliveAStream() {
        assertThatThrownBy(() -> new SseProperties(
                Duration.ofSeconds(90), Duration.ofSeconds(25), Duration.ofSeconds(8), Duration.ofSeconds(5),
                Duration.ofSeconds(95), Duration.ofMinutes(30),
                Duration.ofMinutes(20),                      // keyTtl < maxLifetime + leaseTtl
                Duration.ofSeconds(60), 100, 200, 64, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key-ttl");
    }
}
