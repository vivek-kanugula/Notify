package com.notify.quartz;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.service.NotificationService;
import com.notify.outbox.repository.OutboxEventRepository;
import com.notify.quartz.service.NotificationReconcilerService;
import com.notify.quartz.service.NotificationSchedulerService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationReconcilerFairTraversalTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationSchedulerService schedulerService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private com.notify.notification.repository.NotificationStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private Clock clock;

    private NotificationReconcilerService reconcilerService;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        statusHistoryRepository.deleteAll();
        notificationRepository.deleteAll();

        TenantEntity tenant = new TenantEntity("FairTraversalOrg", "fair-traversal-" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);
        this.tenantId = tenant.getId();

        reconcilerService = new NotificationReconcilerService(
                notificationRepository, notificationService, schedulerService, clock
        );
        reconcilerService.setGracePeriodSeconds(0L);
    }

    @Test
    @DisplayName("Anti-starvation: permanently failing early overdue row advances cursor and never starves later overdue rows")
    void overdueAntiStarvation_failingEarlyRowDoesNotStarveLaterRows() {
        Instant now = Instant.now(clock);

        // Create 4 overdue rows with strictly ascending scheduledAt so ordering is deterministic:
        // n1 (12m ago) < n2 (10m ago) < n3 (8m ago) < n4 (6m ago)
        NotificationEntity n1 = createOverdueScheduled(now.minus(12, ChronoUnit.MINUTES), 0);
        NotificationEntity n2 = createOverdueScheduled(now.minus(10, ChronoUnit.MINUTES), 0);
        NotificationEntity n3 = createOverdueScheduled(now.minus(8, ChronoUnit.MINUTES), 0);
        NotificationEntity n4 = createOverdueScheduled(now.minus(6, ChronoUnit.MINUTES), 0);

        // Make row 1 permanently failing via a mock delegating to real service except for n1 which fails/returns false
        NotificationService mockNotificationService = org.mockito.Mockito.mock(NotificationService.class);
        org.mockito.Mockito.when(mockNotificationService.releaseScheduledNotification(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        )).thenAnswer(inv -> {
            UUID nid = inv.getArgument(1);
            if (n1.getId().equals(nid)) {
                return false; // permanently fails / returns false without advancing status
            }
            return notificationService.releaseScheduledNotification(
                    inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)
            );
        });

        NotificationReconcilerService customReconciler = new NotificationReconcilerService(
                notificationRepository, mockNotificationService, schedulerService, clock
        );
        customReconciler.setGracePeriodSeconds(0L);
        customReconciler.setBatchSize(2);

        // Cycle 1: inspects batch 1 (n1, n2).
        // n1 fails/throws, n2 succeeds and becomes QUEUED.
        // Overdue cursor advances to n2!
        int reconciledCycle1 = customReconciler.reconcileOverdueScheduled();
        assertThat(reconciledCycle1).isEqualTo(1); // n2 reconciled

        assertThat(notificationRepository.findById(n1.getId()).orElseThrow().getStatus())
                .isEqualTo(NotificationStatus.SCHEDULED); // still failing
        assertThat(notificationRepository.findById(n2.getId()).orElseThrow().getStatus())
                .isEqualTo(NotificationStatus.QUEUED);

        // Cursor should now point to n2
        assertThat(customReconciler.getOverdueScheduledCursorId()).isEqualTo(n2.getId());

        // Cycle 2: inspects batch 2 (n3, n4) because cursor is at n2.
        // n3 and n4 ARE INSPECTED AND RECONCILED! They were NOT starved by permanently failing n1!
        int reconciledCycle2 = customReconciler.reconcileOverdueScheduled();
        assertThat(reconciledCycle2).isEqualTo(2); // n3 and n4 reconciled!

        assertThat(notificationRepository.findById(n3.getId()).orElseThrow().getStatus())
                .isEqualTo(NotificationStatus.QUEUED);
        assertThat(notificationRepository.findById(n4.getId()).orElseThrow().getStatus())
                .isEqualTo(NotificationStatus.QUEUED);

        // Cursor points to n4 after batch 2 of size 2
        assertThat(customReconciler.getOverdueScheduledCursorId()).isEqualTo(n4.getId());

        // Cycle 3: queries after n4 (empty), wraps around to beginning finding [n1] (size 1 < batchSize 2),
        // visits remaining overdue n1 (fails), and cursor resets to null!
        int reconciledCycle3 = customReconciler.reconcileOverdueScheduled();
        assertThat(reconciledCycle3).isEqualTo(0); // n1 still fails
        assertThat(customReconciler.getOverdueScheduledCursorId()).isNull();
    }

    @Test
    @DisplayName("Fair keyset traversal: multi-batch future scan, cursor wraparound, and restoring trigger beyond batch 1")
    void fairKeysetTraversal_multiBatchAndWraparound_restoresBeyondFirstBatch() {
        // Set batch size = 2 to test multi-batch traversal across cycles
        reconcilerService.setBatchSize(2);
        reconcilerService.setNearDueMinutes(1); // Small near-due window so items in 1+ hours are tested via keyset scan

        Instant baseFuture = Instant.now(clock).plus(2, ChronoUnit.HOURS);

        // Create 5 future scheduled notifications (Batch 1: f1, f2; Batch 2: f3, f4; Batch 3: f5)
        List<NotificationEntity> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            NotificationEntity f = new NotificationEntity();
            f.setTenantId(tenantId);
            f.setChannel(Channel.EMAIL);
            f.setStatus(NotificationStatus.SCHEDULED);
            f.setRecipient("future" + i + "@example.com");
            f.setSubject("Subject " + i);
            f.setBody("Body " + i);
            f.setDispatchGeneration(0);
            f.setScheduledAt(baseFuture.plus(i * 10, ChronoUnit.MINUTES));
            items.add(notificationRepository.save(f));
        }

        NotificationEntity f1 = items.get(0);
        NotificationEntity f2 = items.get(1);
        NotificationEntity f3 = items.get(2);
        NotificationEntity f4 = items.get(3);
        NotificationEntity f5 = items.get(4);

        // Pre-create triggers for f1, f2, f3, f5; leave f4 (in batch 2) WITHOUT a trigger!
        schedulerService.scheduleNotificationRelease(tenantId, f1.getId(), f1.getScheduledAt(), 0, null);
        schedulerService.scheduleNotificationRelease(tenantId, f2.getId(), f2.getScheduledAt(), 0, null);
        schedulerService.scheduleNotificationRelease(tenantId, f3.getId(), f3.getScheduledAt(), 0, null);
        schedulerService.scheduleNotificationRelease(tenantId, f5.getId(), f5.getScheduledAt(), 0, null);

        assertThat(schedulerService.hasReleaseTrigger(f4.getId())).isFalse();

        // Cycle 1: scans Batch 1 (f1, f2). Cursor advances to f2.
        int restoredCycle1 = reconcilerService.restoreMissingTriggers();
        assertThat(restoredCycle1).isEqualTo(0);
        assertThat(reconcilerService.getFutureScheduledCursorId()).isEqualTo(f2.getId());
        assertThat(schedulerService.hasReleaseTrigger(f4.getId())).isFalse();

        // Cycle 2: scans Batch 2 (f3, f4) starting after f2.
        // MISSING TRIGGER FOR f4 BEYOND FIRST BATCH IS DETECTED AND RESTORED!
        int restoredCycle2 = reconcilerService.restoreMissingTriggers();
        assertThat(restoredCycle2).isEqualTo(1);
        assertThat(schedulerService.hasReleaseTrigger(f4.getId())).isTrue();
        assertThat(reconcilerService.getFutureScheduledCursorId()).isEqualTo(f4.getId());

        // Cycle 3: scans Batch 3 (f5). Reaches end of rows (size 1 < batchSize 2) -> wraps around to null!
        int restoredCycle3 = reconcilerService.restoreMissingTriggers();
        assertThat(restoredCycle3).isEqualTo(0);
        assertThat(reconcilerService.getFutureScheduledCursorId()).isNull();

        // Cycle 4: wraps around and starts from Batch 1 (f1, f2) again!
        int restoredCycle4 = reconcilerService.restoreMissingTriggers();
        assertThat(restoredCycle4).isEqualTo(0);
        assertThat(reconcilerService.getFutureScheduledCursorId()).isEqualTo(f2.getId());

        // Cleanup triggers
        items.forEach(it -> schedulerService.cancelScheduledRelease(it.getId()));
    }

    @Test
    @DisplayName("Near-due trigger prioritization: near-due missing trigger is restored in the first cycle even when keyset cursor is far away")
    void nearDuePrioritization_restoredImmediatelyRegardlessOfCursor() {
        reconcilerService.setBatchSize(2);
        reconcilerService.setNearDueMinutes(5);

        Instant now = Instant.now(clock);

        // Create 3 distant future items (10, 11, 12 hours away) so batch 1 is full without wraparound
        NotificationEntity far1 = createFutureScheduled(now.plus(10, ChronoUnit.HOURS));
        NotificationEntity far2 = createFutureScheduled(now.plus(11, ChronoUnit.HOURS));
        NotificationEntity far3 = createFutureScheduled(now.plus(12, ChronoUnit.HOURS));
        schedulerService.scheduleNotificationRelease(tenantId, far1.getId(), far1.getScheduledAt(), 0, null);
        schedulerService.scheduleNotificationRelease(tenantId, far2.getId(), far2.getScheduledAt(), 0, null);
        schedulerService.scheduleNotificationRelease(tenantId, far3.getId(), far3.getScheduledAt(), 0, null);

        // Advance cursor to far2 (batch 1 of size 2)
        reconcilerService.restoreMissingTriggers();
        assertThat(reconcilerService.getFutureScheduledCursorId()).isEqualTo(far2.getId());

        // Now create a near-due notification (due in 2 minutes) with MISSING trigger!
        NotificationEntity nearDue = createFutureScheduled(now.plus(2, ChronoUnit.MINUTES));
        assertThat(schedulerService.hasReleaseTrigger(nearDue.getId())).isFalse();

        // Even though keyset cursor is pointing to far2 (11 hours away), the prioritized near-due scan
        // detects and restores nearDue in the very next cycle!
        int restored = reconcilerService.restoreMissingTriggers();
        assertThat(restored).isEqualTo(1);
        assertThat(schedulerService.hasReleaseTrigger(nearDue.getId())).isTrue();

        // Cleanup
        schedulerService.cancelScheduledRelease(far1.getId());
        schedulerService.cancelScheduledRelease(far2.getId());
        schedulerService.cancelScheduledRelease(far3.getId());
        schedulerService.cancelScheduledRelease(nearDue.getId());
    }

    @Test
    @DisplayName("Near-due scale: 120 near-due rows where first 50 have triggers and later 70 do not — all missing triggers restored in cycle")
    void nearDueScale_120Rows_first50HaveTriggers_later70RestoredInCycle() {
        reconcilerService.setBatchSize(50);
        reconcilerService.setNearDueMinutes(5);
        reconcilerService.setMaxNearDueBatchesPerCycle(10);

        Instant now = Instant.now(clock);
        List<NotificationEntity> items = new ArrayList<>();

        // Create 120 near-due scheduled rows spaced across 1 to 4 minutes in the future
        for (int i = 0; i < 120; i++) {
            Instant scheduledAt = now.plus(60 + (i * 2), ChronoUnit.SECONDS); // 60s .. 298s (within 5 min nearDueCutoff)
            NotificationEntity n = createFutureScheduled(scheduledAt);
            items.add(n);

            if (i < 50) {
                // First 50 already have Quartz release triggers
                schedulerService.scheduleNotificationRelease(tenantId, n.getId(), scheduledAt, 0, null);
                assertThat(schedulerService.hasReleaseTrigger(n.getId())).isTrue();
            } else {
                // Remaining 70 rows DO NOT have triggers
                assertThat(schedulerService.hasReleaseTrigger(n.getId())).isFalse();
            }
        }

        // Measure execution time of bounded near-due restoration
        long startTime = System.currentTimeMillis();
        int restored = reconcilerService.restoreMissingTriggers();
        long durationMs = System.currentTimeMillis() - startTime;

        // All 70 missing triggers must be restored within this single cycle!
        assertThat(restored).isEqualTo(70);

        // Verify all 120 items now have release triggers
        for (NotificationEntity item : items) {
            assertThat(schedulerService.hasReleaseTrigger(item.getId()))
                    .as("Trigger must exist for notification " + item.getId())
                    .isTrue();
        }

        // Cleanup triggers
        items.forEach(it -> schedulerService.cancelScheduledRelease(it.getId()));
    }

    @Test
    @DisplayName("Near-due wraparound (scheduled): cycle starting at non-null cursor reaches short final page and wraps around to restore earlier missing trigger")
    void nearDueWraparound_scheduled_startsNonNullableCursor_reachesShortPage_restoresMissingTriggerInEarlierRow() {
        reconcilerService.setBatchSize(50);
        reconcilerService.setNearDueMinutes(5);
        reconcilerService.setMaxNearDueBatchesPerCycle(10);

        Instant now = Instant.now(clock);
        List<NotificationEntity> items = new ArrayList<>();

        // Create 70 near-due scheduled rows: rows 0..49 (batch 1), rows 50..69 (batch 2: short final page of 20 rows)
        for (int i = 0; i < 70; i++) {
            Instant scheduledAt = now.plus(60 + (i * 2), ChronoUnit.SECONDS);
            NotificationEntity n = createFutureScheduled(scheduledAt);
            items.add(n);

            if (i != 10) {
                // Every row EXCEPT row 10 already has a release trigger
                schedulerService.scheduleNotificationRelease(tenantId, n.getId(), scheduledAt, 0, null);
                assertThat(schedulerService.hasReleaseTrigger(n.getId())).isTrue();
            } else {
                // Row 10 is in the earlier part of the set (before initial cursor at row 39) with MISSING trigger!
                assertThat(schedulerService.hasReleaseTrigger(n.getId())).isFalse();
            }
        }

        // Initialize cycle cursor to row 39 (so initial query after cursor returns rows 40..69 = 30 rows = short page)
        NotificationEntity cursorEntity = items.get(39);
        reconcilerService.setNearDueScheduledCursor(cursorEntity.getScheduledAt(), cursorEntity.getId());
        assertThat(reconcilerService.getNearDueScheduledCursorId()).isEqualTo(cursorEntity.getId());

        // Execute single cycle: should traverse rows 40..69, reach short page, wrap around to rows 0..39,
        // and restore the missing trigger on row 10!
        int restored = reconcilerService.restoreMissingTriggers();

        // Exactly 1 trigger (row 10) restored in this single cycle
        assertThat(restored).isEqualTo(1);
        assertThat(schedulerService.hasReleaseTrigger(items.get(10).getId())).isTrue();

        // All 70 items now have release triggers
        for (NotificationEntity item : items) {
            assertThat(schedulerService.hasReleaseTrigger(item.getId())).isTrue();
        }

        // Keyset cursor resets to null after full traversal
        assertThat(reconcilerService.getNearDueScheduledCursorId()).isNull();

        // Cleanup
        items.forEach(it -> schedulerService.cancelScheduledRelease(it.getId()));
    }

    @Test
    @DisplayName("Near-due wraparound (retrying): cycle starting at non-null cursor reaches short final page and wraps around to restore earlier missing trigger")
    void nearDueWraparound_retrying_startsNonNullableCursor_reachesShortPage_restoresMissingTriggerInEarlierRow() {
        reconcilerService.setBatchSize(50);
        reconcilerService.setNearDueMinutes(5);
        reconcilerService.setMaxNearDueBatchesPerCycle(10);

        Instant now = Instant.now(clock);
        List<NotificationEntity> items = new ArrayList<>();

        // Create 70 near-due retrying rows
        for (int i = 0; i < 70; i++) {
            Instant nextRetryAt = now.plus(60 + (i * 2), ChronoUnit.SECONDS);
            NotificationEntity n = createNearDueRetrying(nextRetryAt);
            items.add(n);

            if (i != 10) {
                // Every row EXCEPT row 10 has a retry trigger
                schedulerService.scheduleNotificationRetry(tenantId, n.getId(), nextRetryAt, 1);
                assertThat(schedulerService.hasRetryTrigger(n.getId())).isTrue();
            } else {
                // Row 10 is before the cursor at row 39 with MISSING retry trigger
                assertThat(schedulerService.hasRetryTrigger(n.getId())).isFalse();
            }
        }

        // Initialize cycle cursor to row 39
        NotificationEntity cursorEntity = items.get(39);
        reconcilerService.setNearDueRetryingCursor(cursorEntity.getNextRetryAt(), cursorEntity.getId());
        assertThat(reconcilerService.getNearDueRetryingCursorId()).isEqualTo(cursorEntity.getId());

        // Execute single cycle
        int restored = reconcilerService.restoreMissingTriggers();

        // Exactly 1 trigger (row 10) restored in this single cycle
        assertThat(restored).isEqualTo(1);
        assertThat(schedulerService.hasRetryTrigger(items.get(10).getId())).isTrue();

        // All 70 items now have retry triggers
        for (NotificationEntity item : items) {
            assertThat(schedulerService.hasRetryTrigger(item.getId())).isTrue();
        }

        // Keyset cursor resets to null after full traversal
        assertThat(reconcilerService.getNearDueRetryingCursorId()).isNull();

        // Cleanup
        items.forEach(it -> schedulerService.cancelRetryTrigger(it.getId()));
    }

    private NotificationEntity createOverdueScheduled(Instant scheduledAt, int gen) {
        NotificationEntity n = new NotificationEntity();
        n.setTenantId(tenantId);
        n.setChannel(Channel.EMAIL);
        n.setStatus(NotificationStatus.SCHEDULED);
        n.setRecipient("overdue-" + UUID.randomUUID() + "@example.com");
        n.setSubject("Subject");
        n.setBody("Body");
        n.setDispatchGeneration(gen);
        n.setScheduledAt(scheduledAt);
        return notificationRepository.save(n);
    }

    private NotificationEntity createFutureScheduled(Instant scheduledAt) {
        NotificationEntity n = new NotificationEntity();
        n.setTenantId(tenantId);
        n.setChannel(Channel.EMAIL);
        n.setStatus(NotificationStatus.SCHEDULED);
        n.setRecipient("future-" + UUID.randomUUID() + "@example.com");
        n.setSubject("Subject");
        n.setBody("Body");
        n.setDispatchGeneration(0);
        n.setScheduledAt(scheduledAt);
        return notificationRepository.save(n);
    }

    private NotificationEntity createNearDueRetrying(Instant nextRetryAt) {
        NotificationEntity n = new NotificationEntity();
        n.setTenantId(tenantId);
        n.setChannel(Channel.EMAIL);
        n.setStatus(NotificationStatus.RETRYING);
        n.setRecipient("retrying-" + UUID.randomUUID() + "@example.com");
        n.setSubject("Subject");
        n.setBody("Body");
        n.setDispatchGeneration(1);
        n.setNextRetryAt(nextRetryAt);
        return notificationRepository.save(n);
    }
}
