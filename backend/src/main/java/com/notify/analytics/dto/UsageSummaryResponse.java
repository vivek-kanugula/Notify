package com.notify.analytics.dto;

import com.notify.common.model.Channel;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant usage summary over [from, to) anchored on created_at ().
 * {@code deliverySuccessRate = totalDelivered / (totalDelivered + totalFailed)}, 0.0 when the sum is 0.
 */
public record UsageSummaryResponse(
        UUID tenantId,
        Instant from,
        Instant to,
        long totalAccepted,
        long totalDelivered,
        long totalFailed,
        long totalCancelled,
        long totalProcessing,
        double deliverySuccessRate,
        Map<Channel, ChannelCounts> byChannel,
        Map<String, Long> statusCounts
) {
    public UsageSummaryResponse(
            UUID tenantId,
            Instant from,
            Instant to,
            long totalAccepted,
            long totalDelivered,
            long totalFailed,
            long totalCancelled,
            long totalProcessing,
            double deliverySuccessRate,
            Map<Channel, ChannelCounts> byChannel) {
        this(tenantId, from, to, totalAccepted, totalDelivered, totalFailed, totalCancelled, totalProcessing,
                deliverySuccessRate, byChannel, Map.of());
    }

    public record ChannelCounts(long accepted, long delivered, long failed) {}
}
