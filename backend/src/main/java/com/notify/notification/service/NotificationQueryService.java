package com.notify.notification.service;

import com.notify.common.model.Actor;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only boundary of the notification feature for analytics and admin reads (/ , seam M2).
 *
 * <p>Other features ({@code com.notify.analytics}, {@code com.notify.admin}) depend on this interface and
 * never on {@code NotificationRepository} or {@code NotificationEntity}. All aggregates count distinct
 * notifications (never delivery attempts) and anchor time windows on {@code created_at} (/ M3).
 */
public interface NotificationQueryService {

    List<StatusCount> statusCountsForTenant(UUID tenantId, Instant from, Instant to);

    List<ChannelStatusCount> channelStatusCountsForTenant(UUID tenantId, Instant from, Instant to);

    List<TimeBucketCount> timeseriesForTenant(UUID tenantId, Instant from, Instant to, String interval);

    List<StatusCount> statusCountsGlobal(Instant from, Instant to);

    LatencyPercentiles latencyPercentilesGlobal(Instant from, Instant to);

    List<TenantVolume> topTenantsByVolume(Instant from, Instant to, int limit);

    List<FailureReason> topFailureReasons(Instant from, Instant to, int limit);

    long queueDepthGlobal();

    long dlqCountGlobal();

    /** Global count of notifications in one status (/ metrics gauges). */
    long countByStatusGlobal(NotificationStatus status);

    default Page<NotificationView> adminSearch(UUID tenantId, Channel channel, NotificationStatus status,
                                               Instant from, Instant to, Pageable pageable) {
        return adminSearch(tenantId, null, channel, status, from, to, pageable);
    }

    Page<NotificationView> adminSearch(UUID tenantId, String tenantName, Channel channel, NotificationStatus status,
                                       Instant from, Instant to, Pageable pageable);

    Optional<NotificationView> findForAdmin(UUID id);

    List<StatusTransition> statusHistory(UUID tenantId, UUID notificationId);

    /** Newest notifications for the given demo tenants, capped at {@code limit} (demo feed). */
    List<NotificationView> recentForTenants(Collection<UUID> tenantIds, int limit);

    // ─── neutral projections (no entity leaks across the feature boundary) ─────────────────────────────────

    record StatusTransition(
            NotificationStatus fromStatus,
            NotificationStatus toStatus,
            String reason,
            Actor actor,
            Instant occurredAt
    ) {}

    record StatusCount(NotificationStatus status, long count) {}

    record ChannelStatusCount(Channel channel, NotificationStatus status, long count) {}

    record TimeBucketCount(Instant bucket, NotificationStatus status, long count) {}

    record FailureReason(String reason, long count) {}

    record TenantVolume(UUID tenantId, long volume) {}

    record LatencyPercentiles(long p50, long p95, long p99) {}

    record NotificationView(
            UUID id,
            UUID tenantId,
            String tenantName,
            Channel channel,
            NotificationStatus status,
            String recipient,
            String subject,
            Instant scheduledAt,
            Instant createdAt,
            Instant firstProcessingAt,
            Instant completedAt,
            int attemptCount,
            String lastErrorCode,
            String provider,
            String providerMessageId,
            String metadata
    ) {
        public NotificationView(
                UUID id, UUID tenantId, Channel channel, NotificationStatus status,
                String recipient, String subject, Instant scheduledAt, Instant createdAt,
                Instant firstProcessingAt, Instant completedAt, int attemptCount,
                String lastErrorCode, String provider, String providerMessageId, String metadata) {
            this(id, tenantId, null, channel, status, recipient, subject, scheduledAt, createdAt,
                    firstProcessingAt, completedAt, attemptCount, lastErrorCode, provider, providerMessageId, metadata);
        }
    }
}
