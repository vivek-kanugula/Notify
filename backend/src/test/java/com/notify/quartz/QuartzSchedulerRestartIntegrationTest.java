package com.notify.quartz;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.NotifyBackendApplication;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.common.model.OutboxStatus;
import com.notify.notification.dto.CreateNotificationRequest;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.service.NotificationService;
import com.notify.outbox.repository.OutboxEventRepository;
import com.notify.quartz.service.NotificationSchedulerService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import com.notify.notification.repository.NotificationStatusHistoryRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DirtiesContext
@TestPropertySource(properties = {
        "spring.quartz.scheduler-name=restart-test-sched"
})
class QuartzSchedulerRestartIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private Clock clock;

    private static final String SCHEDULER_NAME = "restart-test-sched";

    @Test
    @DisplayName("Restart Acceptance: scheduled trigger survives scheduler/application shutdown and fires once when due in restarted application")
    void scheduledTrigger_survivesApplicationRestart_andFiresOnceWhenDue() throws Exception {
        // 1. Arrange: Create tenant and scheduled notification due in 2 seconds
        TenantEntity tenant = new TenantEntity("RestartOrg", "restart-org-" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);
        UUID tenantId = tenant.getId();

        Instant scheduledAt = Instant.now(clock).plus(2, ChronoUnit.SECONDS);
        CreateNotificationRequest request = new CreateNotificationRequest(
                Channel.EMAIL,
                "restart-due@example.com",
                "Restart Test Subject",
                "Restart Test Body",
                null,
                null,
                null,
                null,
                null,
                scheduledAt
        );

        NotificationService.AcceptanceResult result = notificationService.acceptNotification(tenantId, request, null);
        UUID notificationId = result.notificationId();
        assertThat(result.status()).isEqualTo(NotificationStatus.SCHEDULED);

        // Verify trigger is durably stored in PostgreSQL qrtz_triggers table
        String triggerName = "release-trg-" + notificationId;
        Integer triggerCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM qrtz_triggers WHERE sched_name = ? AND trigger_name = ? AND trigger_group = ?",
                Integer.class,
                SCHEDULER_NAME,
                triggerName,
                NotificationSchedulerService.RELEASE_GROUP
        );
        assertThat(triggerCount).isEqualTo(1);

        // 2. Act: Shut down the scheduler in the initial context to simulate process termination
        scheduler.shutdown(true);
        assertThat(scheduler.isShutdown()).isTrue();

        // 3. Restart: Boot a fresh Spring ApplicationContext against the same PostgreSQL database
        String[] args = {
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.quartz.scheduler-name=" + SCHEDULER_NAME,
                "--spring.quartz.auto-startup=true",
                "--server.port=0",
                "--notify.reconciler.enabled=false",
                "--notify.outbox.relay-enabled=false"
        };
        try (ConfigurableApplicationContext restartedContext = new SpringApplicationBuilder(NotifyBackendApplication.class)
                .profiles("test")
                .run(args)) {

            Scheduler restartedScheduler = restartedContext.getBean(Scheduler.class);
            if (!restartedScheduler.isStarted()) {
                restartedScheduler.start();
            }

            NotificationRepository restartedNotifRepo = restartedContext.getBean(NotificationRepository.class);
            OutboxEventRepository restartedOutboxRepo = restartedContext.getBean(OutboxEventRepository.class);

            // 4. Await: Verify the restarted scheduler picks up the trigger, waits until due, and fires once
            await().atMost(15, TimeUnit.SECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        NotificationEntity entity = restartedNotifRepo.findByIdAndTenantId(notificationId, tenantId).orElse(null);
                        assertThat(entity).isNotNull();
                        assertThat(entity.getStatus())
                                .as("Notification must transition to QUEUED upon scheduled release by restarted scheduler")
                                .isEqualTo(NotificationStatus.QUEUED);
                        assertThat(entity.getDispatchGeneration())
                                .as("Dispatch generation must increment to 1")
                                .isEqualTo(1);
                    });

            // 5. Assert: Verify exactly one outbox event was created with NOTIFICATION_QUEUED and topic
            var allOutboxEvents = restartedOutboxRepo.findAll().stream()
                    .filter(e -> e.getAggregateId().equals(notificationId))
                    .toList();
            assertThat(allOutboxEvents)
                    .hasSize(1)
                    .allSatisfy(e -> {
                        assertThat(e.getStatus()).isEqualTo(OutboxStatus.PENDING);
                        assertThat(e.getEventType()).isEqualTo("NOTIFICATION_QUEUED");
                        assertThat(e.getTopic()).isEqualTo("notify.dispatch.email.v1");
                    });

            // 5b. Assert: Verify exactly one relevant status-history transition (SCHEDULED -> QUEUED)
            NotificationStatusHistoryRepository restartedHistoryRepo = restartedContext.getBean(NotificationStatusHistoryRepository.class);
            var historyTransitions = restartedHistoryRepo.findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenantId, notificationId);
            assertThat(historyTransitions).hasSize(2);
            assertThat(historyTransitions.get(0).getToStatus()).isEqualTo(NotificationStatus.SCHEDULED);
            assertThat(historyTransitions)
                    .filteredOn(h -> h.getToStatus() == NotificationStatus.QUEUED)
                    .hasSize(1)
                    .allSatisfy(h -> {
                        assertThat(h.getFromStatus()).isEqualTo(NotificationStatus.SCHEDULED);
                        assertThat(h.getReason()).isEqualTo("Scheduled release execution");
                    });

            // 6. Assert: Bounded await for Quartz's asynchronous completed-trigger cleanup
            await().atMost(10, TimeUnit.SECONDS)
                    .pollInterval(100, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        Integer remainingTriggers = jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM qrtz_triggers WHERE sched_name = ? AND trigger_name = ? AND trigger_group = ?",
                                Integer.class,
                                SCHEDULER_NAME,
                                triggerName,
                                NotificationSchedulerService.RELEASE_GROUP
                        );
                        assertThat(remainingTriggers).isEqualTo(0);
                    });
        }
    }
}
