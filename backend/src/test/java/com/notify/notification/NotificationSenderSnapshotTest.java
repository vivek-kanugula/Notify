package com.notify.notification;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.notification.dto.CreateNotificationRequest;
import com.notify.notification.service.NotificationService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** S1: sender captured at acceptance from server configuration only (). */
@TestPropertySource(properties = "notify.mail.from=noreply@sender.notify.local")
class NotificationSenderSnapshotTest extends AbstractPostgresIntegrationTest {

    private static final String CONFIGURED = "noreply@sender.notify.local";

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = tenantRepository.save(new TenantEntity("SenderOrg", "sender-org-" + UUID.randomUUID())).getId();
    }

    private String senderOf(UUID notificationId) {
        return jdbc.queryForObject("SELECT sender FROM notifications WHERE id = ?", String.class, notificationId);
    }

    @Test
    @DisplayName("S1 an accepted EMAIL notification stores the configured sender; client metadata cannot choose it")
    void emailSenderComesFromServerConfigurationOnly() {
        CreateNotificationRequest request = new CreateNotificationRequest(
                Channel.EMAIL, "to@sender.notify.local", "Subject", "Body", null, null, null,
                Map.of("sender", "evil@attacker.test", "from", "evil@attacker.test", "replyTo", "evil@attacker.test"),
                null, null);

        UUID id = notificationService.acceptNotification(tenantId, request, null).notificationId();

        assertThat(senderOf(id)).isEqualTo(CONFIGURED);
        assertThat(jdbc.queryForObject("SELECT claim_seq FROM notifications WHERE id = ?", Integer.class, id)).isZero();
    }

    @Test
    @DisplayName("S1 a scheduled EMAIL notification also snapshots the sender at acceptance")
    void scheduledEmailAlsoSnapshotsSender() {
        CreateNotificationRequest request = new CreateNotificationRequest(
                Channel.EMAIL, "to@sender.notify.local", "Subject", "Body", null, null, null, null,
                null, Instant.now().plus(2, ChronoUnit.HOURS));

        UUID id = notificationService.acceptNotification(tenantId, request, null).notificationId();

        assertThat(senderOf(id)).isEqualTo(CONFIGURED);
    }

    @Test
    @DisplayName("S1 non-email channels do not get an email sender")
    void nonEmailChannelHasNoSender() {
        CreateNotificationRequest request = new CreateNotificationRequest(
                Channel.WEBHOOK, "https://hooks.sender.notify.local/x", null, "{\"a\":1}", null, null, null, null, null, null);

        UUID id = notificationService.acceptNotification(tenantId, request, null).notificationId();

        assertThat(senderOf(id)).isNull();
    }

    @Test
    @DisplayName("Accepted notification increments notify.notifications.created counter")
    void acceptedNotificationIncrementsCreatedMetric() {
        var counter = meterRegistry.find("notify.notifications.created").tag("channel", "EMAIL").counter();
        double before = counter != null ? counter.count() : 0.0;

        CreateNotificationRequest request = new CreateNotificationRequest(
                Channel.EMAIL, "metric@sender.notify.local", "Subject", "Body", null, null, null, null, null, null);

        notificationService.acceptNotification(tenantId, request, null);

        double after = meterRegistry.get("notify.notifications.created")
                .tag("channel", "EMAIL")
                .counter()
                .count();

        assertThat(after).isEqualTo(before + 1.0);
    }
}
