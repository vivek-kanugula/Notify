package com.notify.notification.service;

import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.repository.NotificationStatusHistoryRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link NotificationQueryService}: the only class that touches {@link NotificationRepository} for
 * analytics/admin reads. Maps native aggregate rows to the neutral projections.
 */
@Service
public class NotificationQueryServiceImpl implements NotificationQueryService {

    private final NotificationRepository notificationRepository;
    private final NotificationStatusHistoryRepository statusHistoryRepository;

    public NotificationQueryServiceImpl(NotificationRepository notificationRepository,
                                        NotificationStatusHistoryRepository statusHistoryRepository) {
        this.notificationRepository = notificationRepository;
        this.statusHistoryRepository = statusHistoryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StatusCount> statusCountsForTenant(UUID tenantId, Instant from, Instant to) {
        return notificationRepository.statusCountsForTenant(tenantId, from, to).stream()
                .map(r -> new StatusCount(NotificationStatus.valueOf((String) r[0]), asLong(r[1])))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChannelStatusCount> channelStatusCountsForTenant(UUID tenantId, Instant from, Instant to) {
        return notificationRepository.channelStatusCountsForTenant(tenantId, from, to).stream()
                .map(r -> new ChannelStatusCount(
                        Channel.valueOf((String) r[0]),
                        NotificationStatus.valueOf((String) r[1]),
                        asLong(r[2])))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeBucketCount> timeseriesForTenant(UUID tenantId, Instant from, Instant to, String interval) {
        String safeInterval = normalizeInterval(interval);
        return notificationRepository.timeseriesForTenant(tenantId, from, to, safeInterval).stream()
                .map(r -> new TimeBucketCount(
                        asInstant(r[0]),
                        NotificationStatus.valueOf((String) r[1]),
                        asLong(r[2])))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StatusCount> statusCountsGlobal(Instant from, Instant to) {
        return notificationRepository.statusCountsGlobal(from, to).stream()
                .map(r -> new StatusCount(NotificationStatus.valueOf((String) r[0]), asLong(r[1])))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public LatencyPercentiles latencyPercentilesGlobal(Instant from, Instant to) {
        List<Object[]> rows = notificationRepository.latencyPercentilesGlobal(from, to);
        if (rows.isEmpty()) {
            return new LatencyPercentiles(0, 0, 0);
        }
        Object[] r = rows.get(0);
        return new LatencyPercentiles(asRoundedMs(r[0]), asRoundedMs(r[1]), asRoundedMs(r[2]));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantVolume> topTenantsByVolume(Instant from, Instant to, int limit) {
        return notificationRepository.topTenantsByVolume(from, to, limit).stream()
                .map(r -> new TenantVolume(asUuid(r[0]), asLong(r[1])))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FailureReason> topFailureReasons(Instant from, Instant to, int limit) {
        return notificationRepository.topFailureReasonsGlobal(from, to, limit).stream()
                .map(r -> new FailureReason((String) r[0], asLong(r[1])))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long queueDepthGlobal() {
        return notificationRepository.queueDepthGlobal();
    }

    @Override
    @Transactional(readOnly = true)
    public long dlqCountGlobal() {
        return notificationRepository.dlqCountGlobal();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByStatusGlobal(NotificationStatus status) {
        return notificationRepository.countByStatus(status);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationView> adminSearch(UUID tenantId, Channel channel, NotificationStatus status,
                                              Instant from, Instant to, Pageable pageable) {
        Specification<NotificationEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (tenantId != null) {
                predicates.add(cb.equal(root.get("tenantId"), tenantId));
            }
            if (channel != null) {
                predicates.add(cb.equal(root.get("channel"), channel));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return notificationRepository.findAll(spec, pageable).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationView> findForAdmin(UUID id) {
        return notificationRepository.findById(id).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StatusTransition> statusHistory(UUID tenantId, UUID notificationId) {
        return statusHistoryRepository
                .findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenantId, notificationId).stream()
                .map(h -> new StatusTransition(
                        h.getFromStatus(), h.getToStatus(), h.getReason(), h.getActor(), h.getOccurredAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationView> recentForTenants(Collection<UUID> tenantIds, int limit) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            return List.of();
        }
        Pageable page = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        return notificationRepository.findByTenantIdInOrderByCreatedAtDesc(tenantIds, page).stream()
                .map(this::toView)
                .toList();
    }

    private NotificationView toView(NotificationEntity n) {
        return new NotificationView(
                n.getId(), n.getTenantId(), n.getChannel(), n.getStatus(),
                n.getRecipient(), n.getSubject(), n.getScheduledAt(), n.getCreatedAt(),
                n.getFirstProcessingAt(), n.getCompletedAt(), n.getAttemptCount(),
                n.getLastErrorCode(), n.getProvider(), n.getProviderMessageId(), n.getMetadata());
    }

    /** Only 'day' and 'hour' are permitted; anything else falls back to 'day' (never interpolated unchecked). */
    private static String normalizeInterval(String interval) {
        if ("hour".equalsIgnoreCase(interval)) {
            return "hour";
        }
        return "day";
    }

    private static long asLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static long asRoundedMs(Object o) {
        return o == null ? 0L : Math.round(((Number) o).doubleValue());
    }

    private static UUID asUuid(Object o) {
        if (o instanceof UUID u) {
            return u;
        }
        return UUID.fromString(String.valueOf(o));
    }

    private static Instant asInstant(Object o) {
        if (o instanceof Instant i) {
            return i;
        }
        if (o instanceof Timestamp t) {
            return t.toInstant();
        }
        if (o instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        throw new IllegalStateException("Unexpected bucket type: " + (o == null ? "null" : o.getClass()));
    }
}
