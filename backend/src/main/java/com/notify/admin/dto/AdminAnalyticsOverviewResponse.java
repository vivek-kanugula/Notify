package com.notify.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Platform-wide analytics overview across all tenants over [from, to) ().
 * Counts are distinct notifications; latency is E2E per delivered notification (/ M3).
 */
public record AdminAnalyticsOverviewResponse(
        Instant from,
        Instant to,
        long totalNotifications,
        long deliveredCount,
        long failedCount,
        long cancelledCount,
        long activeCount,
        double globalSuccessRate,
        LatencyPercentilesMs latencyPercentilesMs,
        long queueDepth,
        long dlqOpenCount,
        List<TopTenant> topTenants,
        List<TopFailureReason> topFailureReasons,
        Map<String, Long> statusCounts
) {
    public AdminAnalyticsOverviewResponse(
            Instant from,
            Instant to,
            long totalNotifications,
            long deliveredCount,
            long failedCount,
            long cancelledCount,
            long activeCount,
            double globalSuccessRate,
            LatencyPercentilesMs latencyPercentilesMs,
            long queueDepth,
            long dlqOpenCount,
            List<TopTenant> topTenants,
            List<TopFailureReason> topFailureReasons) {
        this(from, to, totalNotifications, deliveredCount, failedCount, cancelledCount, activeCount,
                globalSuccessRate, latencyPercentilesMs, queueDepth, dlqOpenCount, topTenants, topFailureReasons,
                Map.of());
    }

    public record LatencyPercentilesMs(long p50, long p95, long p99) {}

    public record TopTenant(UUID tenantId, String slug, String name, long volume) {}

    public record TopFailureReason(String reason, long count) {}
}
