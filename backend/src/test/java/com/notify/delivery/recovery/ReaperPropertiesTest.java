package com.notify.delivery.recovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The recovery settings and their bounds (/ , decision ).
 *
 * <p>The defaults are asserted literally, so a silent edit shows up here, and the one relationship that matters is
 * enforced in the constructor: a cutoff shorter than any bounded single attempt would reap a merely slow worker and
 * duplicate its send. Nothing else is constrained - in particular the batch and page bounds are independent knobs.
 */
class ReaperPropertiesTest {

    private static ReaperProperties production() {
        return new ReaperProperties(false, Duration.ofMinutes(10), Duration.ofMinutes(1), 100, 10);
    }

    @Test
    @DisplayName("the accepted defaults, and recovery is off until it is switched on")
    void acceptedDefaults() {
        ReaperProperties props = production();

        assertThat(props.enabled()).as("off by default, like every worker in this project").isFalse();
        assertThat(props.stuckAfter()).isEqualTo(Duration.ofMinutes(10));
        assertThat(props.scanInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(props.batchSize()).isEqualTo(100);
        assertThat(props.maxPagesPerScan()).isEqualTo(10);
        assertThat(props.maxRowsPerScan()).as("one scan is bounded").isEqualTo(1000);
    }

    @Test
    @DisplayName("the cutoff must stay far above any bounded single attempt, or a slow worker would be reaped")
    void theCutoffHasAFloor() {
        assertThat(ReaperProperties.MIN_STUCK_AFTER)
                .as("several times the longest bounded attempt: EMAIL is connect 2s + read 8s")
                .isGreaterThanOrEqualTo(Duration.ofSeconds(30));

        assertThatThrownBy(() -> new ReaperProperties(true, Duration.ofSeconds(5), Duration.ofMinutes(1), 100, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stuck-after");
        assertThatThrownBy(() -> new ReaperProperties(true, null, Duration.ofMinutes(1), 100, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the scan interval, batch size and page bound are each independently validated")
    void theOtherBoundsAreValidated() {
        assertThatThrownBy(() -> new ReaperProperties(true, Duration.ofMinutes(10), Duration.ofSeconds(1), 100, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scan-interval");
        assertThatThrownBy(() -> new ReaperProperties(true, Duration.ofMinutes(10), Duration.ofMinutes(1), 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size");
        assertThatThrownBy(() -> new ReaperProperties(true, Duration.ofMinutes(10), Duration.ofMinutes(1), 5000, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size");
        assertThatThrownBy(() -> new ReaperProperties(true, Duration.ofMinutes(10), Duration.ofMinutes(1), 100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-pages-per-scan");
    }

    @Test
    @DisplayName("a faster scaled set is accepted, so an isolated test can shorten the cutoff safely")
    void aScaledSetIsAccepted() {
        ReaperProperties scaled = new ReaperProperties(true, Duration.ofMinutes(1), Duration.ofSeconds(10), 5, 2);

        assertThat(scaled.stuckAfter()).isEqualTo(ReaperProperties.MIN_STUCK_AFTER);
        assertThat(scaled.maxRowsPerScan()).isEqualTo(10);
    }
}
