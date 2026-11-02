package com.notify.analytics.service;

import com.notify.analytics.dto.UsageBucket;
import com.notify.analytics.dto.UsageSummaryResponse;
import com.notify.analytics.dto.UsageTimeseriesResponse;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.notification.service.NotificationQueryService;
import com.notify.notification.service.NotificationQueryService.ChannelStatusCount;
import com.notify.notification.service.NotificationQueryService.StatusCount;
import com.notify.notification.service.NotificationQueryService.TimeBucketCount;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds tenant usage summaries and timeseries from {@link NotificationQueryService} aggregates.
 *
 * <p>Counts are distinct notifications. {@code failed} folds FAILED and DLQ together (both are delivery
 * failures). Success rate = delivered / (delivered + failed), 0.0 when the denominator is 0 (/2, M3).
 */
@Service
public class UsageServiceImpl implements UsageService {

    private final NotificationQueryService notificationQueryService;

    public UsageServiceImpl(NotificationQueryService notificationQueryService) {
        this.notificationQueryService = notificationQueryService;
    }

    @Override
    public UsageSummaryResponse summary(UUID tenantId, Instant from, Instant to) {
        List<StatusCount> statusCounts = notificationQueryService.statusCountsForTenant(tenantId, from, to);

        long total = 0;
        long delivered = 0;
        long failed = 0;
        long cancelled = 0;
        long processing = 0;
        for (StatusCount sc : statusCounts) {
            total += sc.count();
            switch (sc.status()) {
                case DELIVERED -> delivered += sc.count();
                case FAILED, DLQ -> failed += sc.count();
                case CANCELLED -> cancelled += sc.count();
                case PROCESSING -> processing += sc.count();
                default -> { /* CREATED, SCHEDULED, QUEUED, RETRYING contribute only to total */ }
            }
        }

        Map<Channel, long[]> perChannel = new EnumMap<>(Channel.class); // [accepted, delivered, failed]
        for (ChannelStatusCount c : notificationQueryService.channelStatusCountsForTenant(tenantId, from, to)) {
            long[] acc = perChannel.computeIfAbsent(c.channel(), k -> new long[3]);
            acc[0] += c.count();
            if (c.status() == NotificationStatus.DELIVERED) {
                acc[1] += c.count();
            } else if (c.status() == NotificationStatus.FAILED || c.status() == NotificationStatus.DLQ) {
                acc[2] += c.count();
            }
        }
        Map<Channel, UsageSummaryResponse.ChannelCounts> byChannel = new EnumMap<>(Channel.class);
        perChannel.forEach((ch, acc) ->
                byChannel.put(ch, new UsageSummaryResponse.ChannelCounts(acc[0], acc[1], acc[2])));

        return new UsageSummaryResponse(
                tenantId, from, to, total, delivered, failed, cancelled, processing,
                successRate(delivered, failed), byChannel);
    }

    @Override
    public UsageTimeseriesResponse timeseries(UUID tenantId, Instant from, Instant to, String interval) {
        List<TimeBucketCount> rows = notificationQueryService.timeseriesForTenant(tenantId, from, to, interval);

        // Preserve bucket order from the query; fold statuses into delivered/failed/cancelled per bucket.
        Map<Instant, long[]> byBucket = new LinkedHashMap<>(); // [delivered, failed, cancelled]
        for (TimeBucketCount r : rows) {
            long[] acc = byBucket.computeIfAbsent(r.bucket(), k -> new long[3]);
            switch (r.status()) {
                case DELIVERED -> acc[0] += r.count();
                case FAILED, DLQ -> acc[1] += r.count();
                case CANCELLED -> acc[2] += r.count();
                default -> { /* ignore non-terminal in the timeseries */ }
            }
        }

        List<UsageBucket> buckets = byBucket.entrySet().stream()
                .map(e -> new UsageBucket(
                        e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2],
                        successRate(e.getValue()[0], e.getValue()[1])))
                .toList();

        return new UsageTimeseriesResponse(from, to, normalizeInterval(interval), buckets);
    }

    static double successRate(long delivered, long failed) {
        long denom = delivered + failed;
        return denom == 0 ? 0.0 : (double) delivered / denom;
    }

    private static String normalizeInterval(String interval) {
        return "hour".equalsIgnoreCase(interval) ? "hour" : "day";
    }
}
