package com.notify.analytics.dto;

import java.time.Instant;

/**
 * One interval bucket of a tenant usage timeseries ().
 * {@code successRate = delivered / (delivered + failed)}, 0.0 when the sum is 0.
 */
public record UsageBucket(
        Instant bucket,
        long delivered,
        long failed,
        long cancelled,
        double successRate
) {}
