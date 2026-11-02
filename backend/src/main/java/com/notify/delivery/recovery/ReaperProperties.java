package com.notify.delivery.recovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Stuck-work recovery settings ({@code notify.recovery.*}, / , decision ).
 *
 * <p><b>Disabled by default</b>, like every worker in this project: adding recovery cannot change the behaviour of
 * an existing deployment until it is switched on, and the acceptance run enables it explicitly.
 *
 * <p>{@code stuckAfter} must stay far above the longest bounded single attempt - about 10 s for EMAIL
 * (connect 2 s + read 8 s) and the webhook attempt deadline - so that a merely slow worker is never reaped. The
 * constructor enforces a floor of one minute rather than deriving it from those budgets, because deriving it would
 * couple this knob to two other configuration trees for no benefit; one minute is already several times either
 * budget. Worst-case recovery latency is {@code stuckAfter + scanInterval}.
 */
@ConfigurationProperties(prefix = "notify.recovery")
public record ReaperProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("10m") Duration stuckAfter,
        @DefaultValue("1m") Duration scanInterval,
        @DefaultValue("100") int batchSize,
        /** Keyset pages taken in one scan, so a single run is bounded even when the backlog is large. */
        @DefaultValue("10") int maxPagesPerScan
) {

    /** Floor for {@code stuckAfter}: several times the longest bounded attempt on any channel. */
    public static final Duration MIN_STUCK_AFTER = Duration.ofMinutes(1);

    public ReaperProperties {
        if (stuckAfter == null || stuckAfter.compareTo(MIN_STUCK_AFTER) < 0) {
            throw new IllegalArgumentException(
                    "notify.recovery.stuck-after must be at least " + MIN_STUCK_AFTER
                            + ", or a merely slow worker would be reaped and its send duplicated");
        }
        if (scanInterval == null || scanInterval.compareTo(Duration.ofSeconds(10)) < 0) {
            throw new IllegalArgumentException("notify.recovery.scan-interval must be at least 10s");
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("notify.recovery.batch-size must be between 1 and 1000");
        }
        if (maxPagesPerScan < 1) {
            throw new IllegalArgumentException("notify.recovery.max-pages-per-scan must be positive");
        }
    }

    /** Hard bound on rows one scan may examine. */
    public int maxRowsPerScan() {
        return batchSize * maxPagesPerScan;
    }
}
