package com.notify.quartz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.service.NotificationService;
import com.notify.outbox.entity.OutboxEventEntity;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationReconcilerTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationSchedulerService schedulerService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private com.notify.notification.repository.NotificationStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private Clock clock;

    private NotificationReconcilerService reconcilerService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        statusHistoryRepository.deleteAll();
        notificationRepository.deleteAll();
        TenantEntity tenant = new TenantEntity("ReconcilerOrg", "reconciler-org-" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);
        this.tenantId = tenant.getId();

        reconcilerService = new NotificationReconcilerService(
                notificationRepository, notificationService, schedulerService, clock
        );
        reconcilerService.setGracePeriodSeconds(0L); // zero grace period for deterministic testing
    }

    @Test
    @DisplayName("Overdue SCHEDULED notification: reconciled to QUEUED with outbox event and generation bump")
    void overdueScheduled_reconciledToQueued() throws Exception {
        NotificationEntity n = new NotificationEntity();
        n.setTenantId(tenantId);
        n.setChannel(Channel.EMAIL);
        n.setStatus(NotificationStatus.SCHEDULED);
        n.setRecipient("overdue@example.com");
        n.setSubject("Overdue Sub");
        n.setBody("Overdue Body");
        n.setDispatchGeneration(0);
        n.setScheduledAt(Instant.now(clock).minus(5, ChronoUnit.MINUTES));
        n = notificationRepository.save(n);

        int reconciled = reconcilerService.reconcileOverdueScheduled();
        assertThat(reconciled).isEqualTo(1);

        NotificationEntity updated = notificationRepository.findById(n.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(updated.getDispatchGeneration()).isEqualTo(1);

        List<OutboxEventEntity> outbox = outboxEventRepository.findAll();
        assertThat(outbox).hasSize(1);
        var json = objectMapper.readTree(outbox.get(0).getPayload());
        assertThat(json.get("attempt").asInt()).isEqualTo(1);
        assertThat(json.get("generation").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Overdue RETRYING notification: reconciled to QUEUED with attempt incremented and due guard respected")
    void overdueRetrying_reconciledToQueued() throws Exception {
        NotificationEntity n = new NotificationEntity();
        n.setTenantId(tenantId);
        n.setChannel(Channel.WEBHOOK);
        n.setStatus(NotificationStatus.RETRYING);
        n.setRecipient("https://example.com/retry-webhook");
        n.setBody("{\"event\":\"retry\"}");
        n.setDispatchGeneration(1);
        n.setAttemptCount(2);
        n.setNextRetryAt(Instant.now(clock).minus(2, ChronoUnit.MINUTES));
        n = notificationRepository.save(n);

        int reconciled = reconcilerService.reconcileOverdueRetrying();
        assertThat(reconciled).isEqualTo(1);

        NotificationEntity updated = notificationRepository.findById(n.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(updated.getDispatchGeneration()).isEqualTo(2);

        List<OutboxEventEntity> outbox = outboxEventRepository.findAll();
        assertThat(outbox).hasSize(1);
        var json = objectMapper.readTree(outbox.get(0).getPayload());
        assertThat(json.get("attempt").asInt()).isEqualTo(3); // attemptCount (2) + 1
        assertThat(json.get("generation").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("Missing trigger restoration: future SCHEDULED and RETRYING with missing Quartz triggers are restored")
    void missingTrigger_restored() {
        // 1. Future SCHEDULED without Quartz trigger
        NotificationEntity sched = new NotificationEntity();
        sched.setTenantId(tenantId);
        sched.setChannel(Channel.EMAIL);
        sched.setStatus(NotificationStatus.SCHEDULED);
        sched.setRecipient("missing-trg@example.com");
        sched.setSubject("Subject");
        sched.setBody("Body");
        sched.setDispatchGeneration(0);
        sched.setScheduledAt(Instant.now(clock).plus(1, ChronoUnit.HOURS));
        sched = notificationRepository.save(sched);

        // 2. Future RETRYING without Quartz trigger
        NotificationEntity retrying = new NotificationEntity();
        retrying.setTenantId(tenantId);
        retrying.setChannel(Channel.IN_APP);
        retrying.setStatus(NotificationStatus.RETRYING);
        retrying.setRecipient("user-missing");
        retrying.setBody("Body");
        retrying.setDispatchGeneration(1);
        retrying.setNextRetryAt(Instant.now(clock).plus(30, ChronoUnit.MINUTES));
        retrying = notificationRepository.save(retrying);

        assertThat(schedulerService.hasReleaseTrigger(sched.getId())).isFalse();
        assertThat(schedulerService.hasRetryTrigger(retrying.getId())).isFalse();

        int restored = reconcilerService.restoreMissingTriggers();
        assertThat(restored).isEqualTo(2);

        assertThat(schedulerService.hasReleaseTrigger(sched.getId())).isTrue();
        assertThat(schedulerService.hasRetryTrigger(retrying.getId())).isTrue();

        // Cleanup triggers
        schedulerService.cancelScheduledRelease(sched.getId());
        schedulerService.cancelRetryTrigger(retrying.getId());
    }

    @Test
    @DisplayName("Race safety: concurrent reconciler and release execution yields exactly one winner and one outbox event")
    void raceSafety_reconcilerAndRelease_onlyOneWinner() throws Exception {
        NotificationEntity n = new NotificationEntity();
        n.setTenantId(tenantId);
        n.setChannel(Channel.EMAIL);
        n.setStatus(NotificationStatus.SCHEDULED);
        n.setRecipient("race@example.com");
        n.setSubject("Race Subject");
        n.setBody("Race Body");
        n.setDispatchGeneration(0);
        n.setScheduledAt(Instant.now(clock).minus(1, ChronoUnit.MINUTES));
        final NotificationEntity entity = notificationRepository.save(n);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger successCount = new AtomicInteger(0);

        Callable<Boolean> task = () -> {
            barrier.await();
            boolean success = notificationService.releaseScheduledNotification(
                    entity.getTenantId(), entity.getId(), 0
            );
            if (success) {
                successCount.incrementAndGet();
            }
            return success;
        };

        Future<Boolean> f1 = executor.submit(task);
        Future<Boolean> f2 = executor.submit(task);

        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly 1 winner!
        assertThat(successCount.get()).isEqualTo(1);

        NotificationEntity finalEntity = notificationRepository.findById(entity.getId()).orElseThrow();
        assertThat(finalEntity.getStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(finalEntity.getDispatchGeneration()).isEqualTo(1);

        // Exactly 1 outbox event!
        List<OutboxEventEntity> outbox = outboxEventRepository.findAll();
        assertThat(outbox).hasSize(1);
    }
}
