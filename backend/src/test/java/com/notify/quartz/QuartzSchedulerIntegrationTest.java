package com.notify.quartz;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.common.model.OutboxStatus;
import com.notify.notification.dto.CreateNotificationRequest;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.service.NotificationService;
import com.notify.outbox.entity.OutboxEventEntity;
import com.notify.outbox.repository.OutboxEventRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

class QuartzSchedulerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationSchedulerService schedulerService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private Clock clock;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        TenantEntity tenant = new TenantEntity("QuartzTestOrg", "quartz-test-org-" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);
        this.tenantId = tenant.getId();
    }

    @Autowired
    private com.notify.notification.repository.NotificationStatusHistoryRepository historyRepository;

    @Test
    @DisplayName("Scheduled release due-time fence: releaseScheduledNotification guards early execution and succeeds when due")
    void releaseScheduled_guardsEarlyExecution_succeedsWhenDue() throws Exception {
        Instant scheduledAt = Instant.now(clock).plus(1, ChronoUnit.HOURS);
        CreateNotificationRequest request = new CreateNotificationRequest(
                Channel.EMAIL,
                "release@example.com",
                "Release Subject",
                "Release Body",
                null,
                null,
                null,
                null,
                null,
                scheduledAt
        );

        NotificationService.AcceptanceResult result = notificationService.acceptNotification(tenantId, request, null);
        UUID notificationId = result.notificationId();

        // 1. Attempt early release before due time: returns false, no state change, no history added, no outbox event
        boolean earlyReleased = notificationService.releaseScheduledNotification(tenantId, notificationId, 0);
        assertThat(earlyReleased).isFalse();

        NotificationEntity notYetDue = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(notYetDue.getStatus()).isEqualTo(NotificationStatus.SCHEDULED);
        assertThat(notYetDue.getDispatchGeneration()).isEqualTo(0);
        assertThat(notYetDue.getQueuedAt()).isNull();

        var initialHistory = historyRepository.findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenantId, notificationId);
        assertThat(initialHistory).hasSize(1);
        assertThat(initialHistory.get(0).getToStatus()).isEqualTo(NotificationStatus.SCHEDULED);

        assertThat(outboxEventRepository.findAll()).isEmpty();

        // 2. Advance time past scheduledAt: update scheduled_at to past so scheduled_at <= now
        notYetDue.setScheduledAt(Instant.now(clock).minus(5, ChronoUnit.SECONDS));
        notificationRepository.save(notYetDue);

        // 3. Release execution when due (expected generation 0)
        boolean released = notificationService.releaseScheduledNotification(tenantId, notificationId, 0);
        assertThat(released).isTrue();

        NotificationEntity updated = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(updated.getDispatchGeneration()).isEqualTo(1);
        assertThat(updated.getQueuedAt()).isNotNull();

        var dueHistory = historyRepository.findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenantId, notificationId);
        assertThat(dueHistory).hasSize(2);
        assertThat(dueHistory.get(1).getFromStatus()).isEqualTo(NotificationStatus.SCHEDULED);
        assertThat(dueHistory.get(1).getToStatus()).isEqualTo(NotificationStatus.QUEUED);

        // Verify outbox row enqueued with 1-indexed attempt = 1
        List<OutboxEventEntity> outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents).hasSize(1);
        OutboxEventEntity outbox = outboxEvents.get(0);
        assertThat(outbox.getAggregateId()).isEqualTo(notificationId);
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        var json = objectMapper.readTree(outbox.getPayload());
        assertThat(json.get("attempt").asInt()).isEqualTo(1);
        assertThat(json.get("generation").asInt()).isEqualTo(1);

        // 4. Duplicate release attempt returns false and creates no duplicate outbox event
        boolean duplicateReleased = notificationService.releaseScheduledNotification(tenantId, notificationId, 0);
        assertThat(duplicateReleased).isFalse();
        assertThat(outboxEventRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("Retry due-time fence: releaseRetryNotification respects next_retry_at <= now guard")
    void retryDueTimeFence_guardsEarlyExecution_succeedsWhenDue() throws Exception {
        // Manually create a RETRYING notification
        NotificationEntity n = new NotificationEntity();
        n.setTenantId(tenantId);
        n.setChannel(Channel.WEBHOOK);
        n.setStatus(NotificationStatus.RETRYING);
        n.setRecipient("https://example.com/webhook");
        n.setBody("{\"event\":\"test\"}");
        n.setDispatchGeneration(1);
        n.setAttemptCount(1);
        // Due time is 10 minutes in the FUTURE
        n.setNextRetryAt(Instant.now(clock).plus(10, ChronoUnit.MINUTES));
        n = notificationRepository.save(n);

        // 1. Attempt release before due time: must be blocked by due-time fence
        boolean earlyReleased = notificationService.releaseRetryNotification(tenantId, n.getId(), 1);
        assertThat(earlyReleased).isFalse();

        NotificationEntity unchanged = notificationRepository.findById(n.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(unchanged.getDispatchGeneration()).isEqualTo(1);
        assertThat(outboxEventRepository.findAll()).isEmpty();

        // 2. Advance time past nextRetryAt (simulate time due)
        unchanged.setNextRetryAt(Instant.now(clock).minus(5, ChronoUnit.SECONDS));
        notificationRepository.save(unchanged);

        // 3. Now release when due: conditional query matches and succeeds
        boolean dueReleased = notificationService.releaseRetryNotification(tenantId, n.getId(), 1);
        assertThat(dueReleased).isTrue();

        NotificationEntity ready = notificationRepository.findById(n.getId()).orElseThrow();
        assertThat(ready.getStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(ready.getDispatchGeneration()).isEqualTo(2);

        // Outbox event created with attempt = 2 (attemptCount + 1)
        List<OutboxEventEntity> outboxList = outboxEventRepository.findAll();
        assertThat(outboxList).hasSize(1);
        var json = objectMapper.readTree(outboxList.get(0).getPayload());
        assertThat(json.get("attempt").asInt()).isEqualTo(2);
        assertThat(json.get("generation").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("Cancellation race: cancelling before release prevents release from writing outbox event")
    void cancellationRace_cancellingBeforeRelease_preventsRelease() {
        Instant scheduledAt = Instant.now(clock).plus(30, ChronoUnit.MINUTES);
        CreateNotificationRequest request = new CreateNotificationRequest(
                Channel.IN_APP,
                "user-race",
                null,
                "Race Body",
                null,
                null,
                null,
                null,
                null,
                scheduledAt
        );

        NotificationService.AcceptanceResult result = notificationService.acceptNotification(tenantId, request, null);
        UUID notificationId = result.notificationId();

        // Tenant cancels first
        notificationService.cancelNotification(tenantId, notificationId, "Cancelled by user");

        // Quartz trigger or late worker attempts release with expected generation 0
        boolean released = notificationService.releaseScheduledNotification(tenantId, notificationId, 0);
        assertThat(released).isFalse();

        // Row remains CANCELLED, zero outbox events created
        NotificationEntity finalState = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(finalState.getStatus()).isEqualTo(NotificationStatus.CANCELLED);
        assertThat(outboxEventRepository.findAll()).isEmpty();

        // Quartz trigger was unscheduled
        assertThat(schedulerService.hasReleaseTrigger(notificationId)).isFalse();
    }
}
