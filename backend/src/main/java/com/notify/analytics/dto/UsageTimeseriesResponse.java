package com.notify.analytics.dto;

import java.time.Instant;
import java.util.List;

/**
 * Tenant usage timeseries over [from, to) bucketed by {@code interval} ('day' or 'hour') ().
 */
public record UsageTimeseriesResponse(
        Instant from,
        Instant to,
        String interval,
        List<UsageBucket> buckets
) {}
