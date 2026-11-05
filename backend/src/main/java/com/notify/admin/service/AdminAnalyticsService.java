package com.notify.admin.service;

import com.notify.admin.dto.AdminAnalyticsOverviewResponse;
import com.notify.analytics.dto.UsageSummaryResponse;
import com.notify.analytics.service.UsageService;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.notification.service.NotificationQueryService;
import com.notify.notification.service.NotificationQueryService.FailureReason;
import com.notify.notification.service.NotificationQueryService.LatencyPercentiles;
import com.notify.notification.service.NotificationQueryService.StatusCount;
import com.notify.notification.service.NotificationQueryService.TenantVolume;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cross-tenant platform analytics for the admin boundary (/ ). Reads notification aggregates through
 * {@link NotificationQueryService} (seam M2) and resolves tenant display fields via {@link TenantRepository}
 * (an existing admin↔tenant dependency). Per-tenant breakdowns delegate to {@link UsageService}.
 */
@Service
public class AdminAnalyticsService {

    private static final int TOP_LIMIT = 10;

    private final NotificationQueryService notificationQueryService;
    private final UsageService usageService;
    private final TenantRepository tenantRepository;

    public AdminAnalyticsService(NotificationQueryService notificationQueryService,
                                 UsageService usageService,
                                 TenantRepository tenantRepository) {
        this.notificationQueryService = notificationQueryService;
        this.usageService = usageService;
        this.tenantRepository = tenantRepository;
    }

    @Transactional(readOnly = true)
    public AdminAnalyticsOverviewResponse overview(Instant from, Instant to) {
        long total = 0;
        long delivered = 0;
        long failed = 0;
        long cancelled = 0;
        long active = 0;
        Map<String, Long> statusCounts = new java.util.HashMap<>();
        for (com.notify.common.model.NotificationStatus s : com.notify.common.model.NotificationStatus.values()) {
            statusCounts.put(s.name(), 0L);
        }
        for (StatusCount sc : notificationQueryService.statusCountsGlobal(from, to)) {
            statusCounts.put(sc.status().name(), sc.count());
            total += sc.count();
            switch (sc.status()) {
                case DELIVERED -> delivered += sc.count();
                case FAILED, DLQ -> failed += sc.count();
                case CANCELLED -> cancelled += sc.count();
                case CREATED, SCHEDULED, QUEUED, PROCESSING, RETRYING -> active += sc.count();
            }
        }

        LatencyPercentiles p = notificationQueryService.latencyPercentilesGlobal(from, to);

        List<TenantVolume> volumes = notificationQueryService.topTenantsByVolume(from, to, TOP_LIMIT);
        Map<UUID, TenantEntity> tenants = tenantRepository
                .findAllById(volumes.stream().map(TenantVolume::tenantId).toList()).stream()
                .collect(Collectors.toMap(TenantEntity::getId, Function.identity()));
        List<AdminAnalyticsOverviewResponse.TopTenant> topTenants = volumes.stream()
                .map(v -> {
                    TenantEntity t = tenants.get(v.tenantId());
                    return new AdminAnalyticsOverviewResponse.TopTenant(
                            v.tenantId(),
                            t != null ? t.getSlug() : null,
                            t != null ? t.getName() : null,
                            v.volume());
                })
                .toList();

        List<AdminAnalyticsOverviewResponse.TopFailureReason> topFailures =
                notificationQueryService.topFailureReasons(from, to, TOP_LIMIT).stream()
                        .map((FailureReason fr) ->
                                new AdminAnalyticsOverviewResponse.TopFailureReason(fr.reason(), fr.count()))
                        .toList();

        long denom = delivered + failed;
        double successRate = denom == 0 ? 0.0 : (double) delivered / denom;

        return new AdminAnalyticsOverviewResponse(
                from, to, total, delivered, failed, cancelled, active, successRate,
                new AdminAnalyticsOverviewResponse.LatencyPercentilesMs(p.p50(), p.p95(), p.p99()),
                notificationQueryService.queueDepthGlobal(),
                notificationQueryService.dlqCountGlobal(),
                topTenants, topFailures, statusCounts);
    }

    @Transactional(readOnly = true)
    public UsageSummaryResponse tenantUsage(UUID tenantId, Instant from, Instant to) {
        if (!tenantRepository.existsById(tenantId)) {
            throw new NotifyException(ErrorCode.NOT_FOUND, "Tenant not found");
        }
        return usageService.summary(tenantId, from, to);
    }
}
