package com.notify.quartz.service;

import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Periodically reconciles overdue notifications and restores missing Quartz triggers.
 *
 * Operational guarantees:
 * - Bounded queries: both overdue and future scans are strictly limited by batchSize (default 50).
 * - Anti-starvation: overdue scans advance a keyset cursor across cycles so permanently failing
 *   early rows never starve later overdue rows. Wraps around when reaching the end.
 * - Fair future traversal: future triggers are inspected via rotating keyset cursor traversal
 *   (scheduledAt, id) across cycles, with automatic wraparound.
 * - Near-due trigger prioritization: notifications due within nearDueMinutes (default 5 min)
 *   are always scanned first every cycle, guaranteeing detection delay <= 60 seconds.
 * - Honest detection delay:
 *   * Near-due window (<= 5 min): max detection delay is 1 cycle (<= 60 seconds).
 *   * Arbitrary future triggers: max detection delay is ceil(N / batchSize) * cycleInterval.
 */
@Service
@ConditionalOnProperty(name = "notify.reconciler.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationReconcilerService {

    private static final Logger log = LoggerFactory.getLogger(NotificationReconcilerService.class);

    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final NotificationSchedulerService schedulerService;
    private final Clock clock;

    @Value("${notify.reconciler.grace-period-seconds:30}")
    private long gracePeriodSeconds = 30L;

    @Value("${notify.reconciler.batch-size:50}")
    private int batchSize = 50;

    @Value("${notify.reconciler.near-due-minutes:5}")
    private int nearDueMinutes = 5;

    @Value("${notify.reconciler.max-near-due-batches-per-cycle:10}")
    private int maxNearDueBatchesPerCycle = 10;

    // Persistent keyset cursors across reconciliation cycles
    private Instant overdueScheduledCursorAt = null;
    private UUID overdueScheduledCursorId = null;

    private Instant overdueRetryingCursorAt = null;
    private UUID overdueRetryingCursorId = null;

    private Instant nearDueScheduledCursorAt = null;
    private UUID nearDueScheduledCursorId = null;

    private Instant nearDueRetryingCursorAt = null;
    private UUID nearDueRetryingCursorId = null;

    private Instant futureScheduledCursorAt = null;
    private UUID futureScheduledCursorId = null;

    private Instant futureRetryingCursorAt = null;
    private UUID futureRetryingCursorId = null;

    public NotificationReconcilerService(
            NotificationRepository notificationRepository,
            NotificationService notificationService,
            NotificationSchedulerService schedulerService,
            Clock clock
    ) {
        this.notificationRepository = notificationRepository;
        this.notificationService = notificationService;
        this.schedulerService = schedulerService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${notify.reconciler.interval-ms:60000}")
    public int reconcileAll() {
        int overdueScheduled = reconcileOverdueScheduled();
        int overdueRetrying = reconcileOverdueRetrying();
        int restoredTriggers = restoreMissingTriggers();
        int total = overdueScheduled + overdueRetrying + restoredTriggers;
        if (total > 0) {
            log.info("Reconciliation complete: overdueScheduled={}, overdueRetrying={}, restoredTriggers={}",
                    overdueScheduled, overdueRetrying, restoredTriggers);
        }
        return total;
    }

    public int reconcileOverdueScheduled() {
        Instant cutoff = Instant.now(clock).minus(gracePeriodSeconds, ChronoUnit.SECONDS);
        Pageable pageable = PageRequest.of(0, batchSize);

        List<NotificationEntity> overdue;
        if (overdueScheduledCursorAt == null || overdueScheduledCursorId == null) {
            overdue = notificationRepository.findOverdueScheduledFirstBatch(cutoff, pageable);
        } else {
            overdue = notificationRepository.findOverdueScheduledAfterCursor(
                    cutoff, overdueScheduledCursorAt, overdueScheduledCursorId, pageable
            );
            if (overdue.isEmpty()) {
                // Wraparound to beginning
                overdue = notificationRepository.findOverdueScheduledFirstBatch(cutoff, pageable);
            }
        }

        if (overdue.isEmpty()) {
            overdueScheduledCursorAt = null;
            overdueScheduledCursorId = null;
            return 0;
        }

        int reconciled = 0;
        for (NotificationEntity entity : overdue) {
            try {
                boolean success = notificationService.releaseScheduledNotification(
                        entity.getTenantId(), entity.getId(), entity.getDispatchGeneration()
                );
                if (success) {
                    reconciled++;
                    log.info("Reconciled overdue scheduled notification {}", entity.getId());
                }
            } catch (Exception e) {
                log.warn("Failed to reconcile scheduled notification {}: {}", entity.getId(), e.getMessage());
            }
        }

        // Advance cursor to last inspected row to prevent starvation on failing rows
        NotificationEntity last = overdue.get(overdue.size() - 1);
        if (overdue.size() < batchSize) {
            overdueScheduledCursorAt = null;
            overdueScheduledCursorId = null;
        } else {
            overdueScheduledCursorAt = last.getScheduledAt();
            overdueScheduledCursorId = last.getId();
        }

        return reconciled;
    }

    public int reconcileOverdueRetrying() {
        Instant cutoff = Instant.now(clock).minus(gracePeriodSeconds, ChronoUnit.SECONDS);
        Pageable pageable = PageRequest.of(0, batchSize);

        List<NotificationEntity> overdue;
        if (overdueRetryingCursorAt == null || overdueRetryingCursorId == null) {
            overdue = notificationRepository.findOverdueRetryingFirstBatch(cutoff, pageable);
        } else {
            overdue = notificationRepository.findOverdueRetryingAfterCursor(
                    cutoff, overdueRetryingCursorAt, overdueRetryingCursorId, pageable
            );
            if (overdue.isEmpty()) {
                overdue = notificationRepository.findOverdueRetryingFirstBatch(cutoff, pageable);
            }
        }

        if (overdue.isEmpty()) {
            overdueRetryingCursorAt = null;
            overdueRetryingCursorId = null;
            return 0;
        }

        int reconciled = 0;
        for (NotificationEntity entity : overdue) {
            try {
                boolean success = notificationService.releaseRetryNotification(
                        entity.getTenantId(), entity.getId(), entity.getDispatchGeneration()
                );
                if (success) {
                    reconciled++;
                    log.info("Reconciled overdue retry notification {}", entity.getId());
                }
            } catch (Exception e) {
                log.warn("Failed to reconcile retry notification {}: {}", entity.getId(), e.getMessage());
            }
        }

        NotificationEntity last = overdue.get(overdue.size() - 1);
        if (overdue.size() < batchSize) {
            overdueRetryingCursorAt = null;
            overdueRetryingCursorId = null;
        } else {
            overdueRetryingCursorAt = last.getNextRetryAt();
            overdueRetryingCursorId = last.getId();
        }

        return reconciled;
    }

    public int restoreMissingTriggers() {
        Instant now = Instant.now(clock);
        Pageable pageable = PageRequest.of(0, batchSize);
        int restored = 0;

        // 1. Prioritized Near-Due Scan (due within nearDueMinutes): bounded keyset traversal across batches
        restored += reconcileNearDueScheduled(now, pageable);
        restored += reconcileNearDueRetrying(now, pageable);

        // 2. Fair Keyset Traversal across all future scheduled notifications
        List<NotificationEntity> futureScheduled;
        if (futureScheduledCursorAt == null || futureScheduledCursorId == null) {
            futureScheduled = notificationRepository.findFutureScheduledFirstBatch(now, pageable);
        } else {
            futureScheduled = notificationRepository.findFutureScheduledAfterCursor(
                    now, futureScheduledCursorAt, futureScheduledCursorId, pageable
            );
            if (futureScheduled.isEmpty()) {
                // Wraparound
                futureScheduled = notificationRepository.findFutureScheduledFirstBatch(now, pageable);
            }
        }

        if (!futureScheduled.isEmpty()) {
            for (NotificationEntity entity : futureScheduled) {
                if (!schedulerService.hasReleaseTrigger(entity.getId())) {
                    try {
                        schedulerService.scheduleNotificationRelease(
                                entity.getTenantId(), entity.getId(), entity.getScheduledAt(),
                                entity.getDispatchGeneration(), entity.getTraceContext()
                        );
                        restored++;
                        log.info("Restored missing release trigger for scheduled notification {}", entity.getId());
                    } catch (Exception e) {
                        log.warn("Failed to restore release trigger for notification {}: {}", entity.getId(), e.getMessage());
                    }
                }
            }

            NotificationEntity last = futureScheduled.get(futureScheduled.size() - 1);
            if (futureScheduled.size() < batchSize) {
                futureScheduledCursorAt = null;
                futureScheduledCursorId = null;
            } else {
                futureScheduledCursorAt = last.getScheduledAt();
                futureScheduledCursorId = last.getId();
            }
        } else {
            futureScheduledCursorAt = null;
            futureScheduledCursorId = null;
        }

        // 3. Fair Keyset Traversal across all future retrying notifications
        List<NotificationEntity> futureRetrying;
        if (futureRetryingCursorAt == null || futureRetryingCursorId == null) {
            futureRetrying = notificationRepository.findFutureRetryingFirstBatch(now, pageable);
        } else {
            futureRetrying = notificationRepository.findFutureRetryingAfterCursor(
                    now, futureRetryingCursorAt, futureRetryingCursorId, pageable
            );
            if (futureRetrying.isEmpty()) {
                futureRetrying = notificationRepository.findFutureRetryingFirstBatch(now, pageable);
            }
        }

        if (!futureRetrying.isEmpty()) {
            for (NotificationEntity entity : futureRetrying) {
                if (!schedulerService.hasRetryTrigger(entity.getId())) {
                    try {
                        schedulerService.scheduleNotificationRetry(
                                entity.getTenantId(), entity.getId(), entity.getNextRetryAt(), entity.getDispatchGeneration()
                        );
                        restored++;
                        log.info("Restored missing retry trigger for notification {}", entity.getId());
                    } catch (Exception e) {
                        log.warn("Failed to restore retry trigger for notification {}: {}", entity.getId(), e.getMessage());
                    }
                }
            }

            NotificationEntity last = futureRetrying.get(futureRetrying.size() - 1);
            if (futureRetrying.size() < batchSize) {
                futureRetryingCursorAt = null;
                futureRetryingCursorId = null;
            } else {
                futureRetryingCursorAt = last.getNextRetryAt();
                futureRetryingCursorId = last.getId();
            }
        } else {
            futureRetryingCursorAt = null;
            futureRetryingCursorId = null;
        }

        return restored;
    }

    private int reconcileNearDueScheduled(Instant now, Pageable pageable) {
        Instant nearDueCutoff = now.plus(nearDueMinutes, ChronoUnit.MINUTES);
        int restored = 0;
        int batchCount = 0;
        Instant cursorAt = nearDueScheduledCursorAt;
        UUID cursorId = nearDueScheduledCursorId;
        final Instant initialCursorAt = cursorAt;
        final UUID initialCursorId = cursorId;
        boolean hasWrapped = false;

        while (batchCount < maxNearDueBatchesPerCycle) {
            List<NotificationEntity> batch;
            if (cursorAt == null || cursorId == null) {
                batch = notificationRepository.findNearDueScheduled(now, nearDueCutoff, pageable);
            } else {
                batch = notificationRepository.findNearDueScheduledAfterCursor(
                        now, nearDueCutoff, cursorAt, cursorId, pageable
                );
            }

            if (batch.isEmpty()) {
                if (!hasWrapped && initialCursorAt != null && initialCursorId != null) {
                    // Reached end of table after initial cursor; wrap around to scan earlier rows
                    hasWrapped = true;
                    cursorAt = null;
                    cursorId = null;
                    continue;
                } else {
                    // Fully scanned all rows in near-due window
                    cursorAt = null;
                    cursorId = null;
                    break;
                }
            }

            boolean reachedInitialCursor = false;
            for (NotificationEntity entity : batch) {
                if (hasWrapped && initialCursorAt != null && initialCursorId != null
                        && isAfterCursor(entity.getScheduledAt(), entity.getId(), initialCursorAt, initialCursorId)) {
                    // Reached rows already processed in Phase 1 before wraparound; stop to avoid processing twice
                    reachedInitialCursor = true;
                    break;
                }

                if (!schedulerService.hasReleaseTrigger(entity.getId())) {
                    try {
                        schedulerService.scheduleNotificationRelease(
                                entity.getTenantId(), entity.getId(), entity.getScheduledAt(),
                                entity.getDispatchGeneration(), entity.getTraceContext()
                        );
                        restored++;
                        log.info("Restored missing near-due release trigger for notification {}", entity.getId());
                    } catch (Exception e) {
                        log.warn("Failed to restore release trigger for notification {}: {}", entity.getId(), e.getMessage());
                    }
                }
            }

            batchCount++;

            if (reachedInitialCursor) {
                // Completed full cycle across all near-due items
                cursorAt = null;
                cursorId = null;
                break;
            }

            NotificationEntity last = batch.get(batch.size() - 1);
            if (batch.size() < pageable.getPageSize()) {
                // Short final page: reached the end of the near-due window
                if (!hasWrapped && initialCursorAt != null && initialCursorId != null) {
                    hasWrapped = true;
                    cursorAt = null;
                    cursorId = null;
                } else {
                    cursorAt = null;
                    cursorId = null;
                    break;
                }
            } else {
                cursorAt = last.getScheduledAt();
                cursorId = last.getId();
            }
        }

        this.nearDueScheduledCursorAt = cursorAt;
        this.nearDueScheduledCursorId = cursorId;
        return restored;
    }

    private int reconcileNearDueRetrying(Instant now, Pageable pageable) {
        Instant nearDueCutoff = now.plus(nearDueMinutes, ChronoUnit.MINUTES);
        int restored = 0;
        int batchCount = 0;
        Instant cursorAt = nearDueRetryingCursorAt;
        UUID cursorId = nearDueRetryingCursorId;
        final Instant initialCursorAt = cursorAt;
        final UUID initialCursorId = cursorId;
        boolean hasWrapped = false;

        while (batchCount < maxNearDueBatchesPerCycle) {
            List<NotificationEntity> batch;
            if (cursorAt == null || cursorId == null) {
                batch = notificationRepository.findNearDueRetrying(now, nearDueCutoff, pageable);
            } else {
                batch = notificationRepository.findNearDueRetryingAfterCursor(
                        now, nearDueCutoff, cursorAt, cursorId, pageable
                );
            }

            if (batch.isEmpty()) {
                if (!hasWrapped && initialCursorAt != null && initialCursorId != null) {
                    // Reached end of table after initial cursor; wrap around to scan earlier rows
                    hasWrapped = true;
                    cursorAt = null;
                    cursorId = null;
                    continue;
                } else {
                    // Fully scanned all rows in near-due window
                    cursorAt = null;
                    cursorId = null;
                    break;
                }
            }

            boolean reachedInitialCursor = false;
            for (NotificationEntity entity : batch) {
                if (hasWrapped && initialCursorAt != null && initialCursorId != null
                        && isAfterCursor(entity.getNextRetryAt(), entity.getId(), initialCursorAt, initialCursorId)) {
                    // Reached rows already processed in Phase 1 before wraparound; stop to avoid processing twice
                    reachedInitialCursor = true;
                    break;
                }

                if (!schedulerService.hasRetryTrigger(entity.getId())) {
                    try {
                        schedulerService.scheduleNotificationRetry(
                                entity.getTenantId(), entity.getId(), entity.getNextRetryAt(), entity.getDispatchGeneration()
                        );
                        restored++;
                        log.info("Restored missing near-due retry trigger for notification {}", entity.getId());
                    } catch (Exception e) {
                        log.warn("Failed to restore retry trigger for notification {}: {}", entity.getId(), e.getMessage());
                    }
                }
            }

            batchCount++;

            if (reachedInitialCursor) {
                // Completed full cycle across all near-due items
                cursorAt = null;
                cursorId = null;
                break;
            }

            NotificationEntity last = batch.get(batch.size() - 1);
            if (batch.size() < pageable.getPageSize()) {
                // Short final page: reached the end of the near-due window
                if (!hasWrapped && initialCursorAt != null && initialCursorId != null) {
                    hasWrapped = true;
                    cursorAt = null;
                    cursorId = null;
                } else {
                    cursorAt = null;
                    cursorId = null;
                    break;
                }
            } else {
                cursorAt = last.getNextRetryAt();
                cursorId = last.getId();
            }
        }

        this.nearDueRetryingCursorAt = cursorAt;
        this.nearDueRetryingCursorId = cursorId;
        return restored;
    }

    private boolean isAfterCursor(Instant at1, UUID id1, Instant at2, UUID id2) {
        if (at1 == null || at2 == null || id1 == null || id2 == null) {
            return false;
        }
        if (at1.isAfter(at2)) {
            return true;
        }
        if (at1.equals(at2)) {
            return id1.compareTo(id2) > 0;
        }
        return false;
    }

    public void setGracePeriodSeconds(long gracePeriodSeconds) {
        this.gracePeriodSeconds = gracePeriodSeconds;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public void setNearDueMinutes(int nearDueMinutes) {
        this.nearDueMinutes = nearDueMinutes;
    }

    public void setMaxNearDueBatchesPerCycle(int maxNearDueBatchesPerCycle) {
        this.maxNearDueBatchesPerCycle = maxNearDueBatchesPerCycle;
    }

    public int getMaxNearDueBatchesPerCycle() {
        return maxNearDueBatchesPerCycle;
    }

    public UUID getNearDueScheduledCursorId() {
        return nearDueScheduledCursorId;
    }

    public UUID getNearDueRetryingCursorId() {
        return nearDueRetryingCursorId;
    }

    public UUID getFutureScheduledCursorId() {
        return futureScheduledCursorId;
    }

    public UUID getFutureRetryingCursorId() {
        return futureRetryingCursorId;
    }

    public UUID getOverdueScheduledCursorId() {
        return overdueScheduledCursorId;
    }

    public UUID getOverdueRetryingCursorId() {
        return overdueRetryingCursorId;
    }

    public void setNearDueScheduledCursor(Instant at, UUID id) {
        this.nearDueScheduledCursorAt = at;
        this.nearDueScheduledCursorId = id;
    }

    public void setNearDueRetryingCursor(Instant at, UUID id) {
        this.nearDueRetryingCursorAt = at;
        this.nearDueRetryingCursorId = id;
    }
}

