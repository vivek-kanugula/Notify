package com.notify.quartz;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.notification.dto.CreateNotificationRequest;
import com.notify.notification.service.NotificationService;
import com.notify.quartz.service.NotificationSchedulerService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuartzSchedulerTransactionIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationSchedulerService schedulerService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        TenantEntity tenant = new TenantEntity("QuartzAtomicTxOrg", "quartz-tx-org-" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);
        this.tenantId = tenant.getId();
    }

    @Test
    @DisplayName("Commit proof: verified through independent queries that notification and Quartz trigger commit together")
    void commit_atomicProof_notificationAndTriggerBothPersisted() {
        Instant scheduledAt = Instant.now(clock).plus(1, ChronoUnit.HOURS);
        CreateNotificationRequest request = new CreateNotificationRequest(
                Channel.EMAIL,
                "scheduled@example.com",
                "Scheduled Subject",
                "Scheduled Body",
                null,
                null,
                null,
                null,
                null,
                scheduledAt
        );

        NotificationService.AcceptanceResult result = notificationService.acceptNotification(tenantId, request, null);
        UUID notificationId = result.notificationId();

        // Query independent database tables directly via JdbcTemplate
        Integer notificationCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE id = ?",
                Integer.class,
                notificationId
        );
        assertThat(notificationCount).isEqualTo(1);

        String triggerName = "release-trg-" + notificationId;
        String jobName = "release-" + notificationId;

        Integer triggerCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM qrtz_triggers WHERE trigger_name = ? AND trigger_group = ?",
                Integer.class,
                triggerName,
                NotificationSchedulerService.RELEASE_GROUP
        );
        assertThat(triggerCount).isEqualTo(1);

        Integer jobDetailCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM qrtz_job_details WHERE job_name = ? AND job_group = ?",
                Integer.class,
                jobName,
                NotificationSchedulerService.RELEASE_GROUP
        );
        assertThat(jobDetailCount).isEqualTo(1);

        // Verify outbox row count is 0 (SCHEDULED notifications do not create outbox event until released)
        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?",
                Integer.class,
                notificationId
        );
        assertThat(outboxCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Rollback proof: verified through independent queries that Quartz trigger rolls back with notification on transaction abort")
    void rollback_atomicProof_neitherNotificationNorTriggerSurvives() {
        UUID notificationId = UUID.randomUUID();
        String triggerName = "release-trg-" + notificationId;
        String jobName = "release-" + notificationId;
        Instant scheduledAt = Instant.now(clock).plus(2, ChronoUnit.HOURS);

        TransactionTemplate tt = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tt.execute(status -> {
            // 1. Insert notification in the transaction
            jdbcTemplate.update("""
                INSERT INTO notifications (
                    id, tenant_id, channel, status, recipient, subject, body,
                    scheduled_at, dispatch_generation, delivery_cycle, max_attempts, attempt_count,
                    deferral_count, version, created_at, updated_at
                ) VALUES (?, ?, 'EMAIL', 'SCHEDULED', 'rollback@example.com', 'Subject', 'Body',
                          ?, 0, 1, 5, 0, 0, 0, NOW(), NOW())
            """, notificationId, tenantId, java.sql.Timestamp.from(scheduledAt));

            // 2. Schedule Quartz release in the same transaction
            schedulerService.scheduleNotificationRelease(tenantId, notificationId, scheduledAt, 0);

            // 3. Verify in-flight within this transaction before abort
            Integer inTxTriggerCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM qrtz_triggers WHERE trigger_name = ?",
                    Integer.class,
                    triggerName
            );
            assertThat(inTxTriggerCount).isEqualTo(1);

            // 4. Force transaction rollback
            throw new RuntimeException("Simulated transaction rollback to prove atomic Quartz rollback");
        })).isInstanceOf(RuntimeException.class)
           .hasMessageContaining("Simulated transaction rollback");

        // Now query independently: neither notification nor Quartz trigger or job detail exists in DB
        Integer notificationCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE id = ?",
                Integer.class,
                notificationId
        );
        assertThat(notificationCount).isEqualTo(0);

        Integer triggerCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM qrtz_triggers WHERE trigger_name = ? AND trigger_group = ?",
                Integer.class,
                triggerName,
                NotificationSchedulerService.RELEASE_GROUP
        );
        assertThat(triggerCount).isEqualTo(0);

        Integer jobDetailCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM qrtz_job_details WHERE job_name = ? AND job_group = ?",
                Integer.class,
                jobName,
                NotificationSchedulerService.RELEASE_GROUP
        );
        assertThat(jobDetailCount).isEqualTo(0);
    }
}
