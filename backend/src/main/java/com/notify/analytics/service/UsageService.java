package com.notify.analytics.service;

import com.notify.analytics.dto.UsageSummaryResponse;
import com.notify.analytics.dto.UsageTimeseriesResponse;

import java.time.Instant;
import java.util.UUID;

/**
 * Tenant business-usage analytics boundary (/ ). Consumed by the tenant {@code UsageController} and,
 * for per-tenant admin breakdowns, by the admin analytics service.
 */
public interface UsageService {

    UsageSummaryResponse summary(UUID tenantId, Instant from, Instant to);

    UsageTimeseriesResponse timeseries(UUID tenantId, Instant from, Instant to, String interval);
}
