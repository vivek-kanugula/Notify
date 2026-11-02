package com.notify.dispatch;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.common.model.OutboxStatus;
import com.notify.kafka.config.KafkaTopicsConfig;
import com.notify.notification.dto.CreateNotificationRequest;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.service.NotificationService;
import com.notify.outbox.entity.OutboxEventEntity;
import com.notify.outbox.repository.OutboxEventRepository;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchTopicRoutingTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TenantRepository tenantRepository;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        TenantEntity tenant = new TenantEntity("TopicRoutingOrg", "topic-org-" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);
        this.tenantId = tenant.getId();
    }

    @Test
    @DisplayName("Topic routing: Immediate ingress produces exact contracted topics for EMAIL, WEBHOOK, and IN_APP")
    void immediateIngress_producesContractedTopics() {
        // 1. EMAIL
        var emailReq = new CreateNotificationRequest(
                Channel.EMAIL, "user@example.com", "Email Subject", "Email Body",
                null, null, null, null, null, null
        );
        var emailResult = notificationService.acceptNotification(tenantId, emailReq, null);
        List<OutboxEventEntity> emailOutbox = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(emailResult.notificationId()))
                .toList();
        assertThat(emailOutbox).hasSize(1);
        assertThat(emailOutbox.get(0).getTopic()).isEqualTo(KafkaTopicsConfig.TOPIC_DISPATCH_EMAIL);
        assertThat(emailOutbox.get(0).getTopic()).isEqualTo("notify.dispatch.email.v1");

        // 2. WEBHOOK
        var webhookReq = new CreateNotificationRequest(
                Channel.WEBHOOK, "https://webhook.site/test-1", null, "{\"event\":\"ping\"}",
                null, null, null, null, null, null
        );
        var webhookResult = notificationService.acceptNotification(tenantId, webhookReq, null);
        List<OutboxEventEntity> webhookOutbox = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(webhookResult.notificationId()))
                .toList();
        assertThat(webhookOutbox).hasSize(1);
        assertThat(webhookOutbox.get(0).getTopic()).isEqualTo(KafkaTopicsConfig.TOPIC_DISPATCH_WEBHOOK);
        assertThat(webhookOutbox.get(0).getTopic()).isEqualTo("notify.dispatch.webhook.v1");

        // 3. IN_APP -> must produce notify.dispatch.inapp.v1 (NO UNDERSCORE)
        var inappReq = new CreateNotificationRequest(
                Channel.IN_APP, "user-456", null, "Hello In-App notification",
                null, null, null, null, null, null
        );
        var inappResult = notificationService.acceptNotification(tenantId, inappReq, null);
        List<OutboxEventEntity> inappOutbox = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(inappResult.notificationId()))
                .toList();
        assertThat(inappOutbox).hasSize(1);
        assertThat(inappOutbox.get(0).getTopic()).isEqualTo(KafkaTopicsConfig.TOPIC_DISPATCH_INAPP);
        assertThat(inappOutbox.get(0).getTopic()).isEqualTo("notify.dispatch.inapp.v1");
        assertThat(inappOutbox.get(0).getTopic())
                .as("Must never produce rogue topic notify.dispatch.in_app.v1 with underscore")
                .isNotEqualTo("notify.dispatch.in_app.v1");
    }

    @Test
    @DisplayName("Topic routing: Scheduled release produces exact contracted topics, especially IN_APP -> notify.dispatch.inapp.v1")
    void scheduledRelease_producesContractedTopics() {
        for (Channel channel : Channel.values()) {
            NotificationEntity entity = new NotificationEntity();
            entity.setTenantId(tenantId);
            entity.setChannel(channel);
            entity.setStatus(NotificationStatus.SCHEDULED);
            entity.setRecipient(channel == Channel.WEBHOOK ? "https://example.com/hook" : "recip-" + channel);
            entity.setSubject("Scheduled " + channel);
            entity.setBody("Body " + channel);
            entity.setDispatchGeneration(0);
            entity.setScheduledAt(Instant.now().minusSeconds(10));
            NotificationEntity saved = notificationRepository.save(entity);

            boolean released = notificationService.releaseScheduledNotification(tenantId, saved.getId(), 0);
            assertThat(released).isTrue();

            List<OutboxEventEntity> outbox = outboxEventRepository.findAll().stream()
                    .filter(e -> e.getAggregateId().equals(saved.getId()))
                    .toList();
            assertThat(outbox).hasSize(1);

            String expectedTopic = KafkaTopicsConfig.resolveDispatchTopic(channel);
            assertThat(outbox.get(0).getTopic()).isEqualTo(expectedTopic);
            assertThat(outbox.get(0).getTopic()).doesNotContain("in_app");
        }
    }

    @Test
    @DisplayName("Topic routing: Retry release produces exact contracted topics and eventType NOTIFICATION_RETRY_DUE")
    void retryRelease_producesContractedTopicsAndRetryDueEventType() {
        for (Channel channel : Channel.values()) {
            NotificationEntity entity = new NotificationEntity();
            entity.setTenantId(tenantId);
            entity.setChannel(channel);
            entity.setStatus(NotificationStatus.RETRYING);
            entity.setRecipient(channel == Channel.WEBHOOK ? "https://example.com/hook" : "retry-" + channel);
            entity.setSubject("Retry " + channel);
            entity.setBody("Body " + channel);
            entity.setDispatchGeneration(1);
            entity.setNextRetryAt(Instant.now().minusSeconds(5));
            NotificationEntity saved = notificationRepository.save(entity);

            boolean released = notificationService.releaseRetryNotification(tenantId, saved.getId(), 1);
            assertThat(released).isTrue();

            List<OutboxEventEntity> outbox = outboxEventRepository.findAll().stream()
                    .filter(e -> e.getAggregateId().equals(saved.getId()))
                    .toList();
            assertThat(outbox).hasSize(1);

            OutboxEventEntity event = outbox.get(0);
            String expectedTopic = KafkaTopicsConfig.resolveDispatchTopic(channel);
            assertThat(event.getTopic()).isEqualTo(expectedTopic);
            assertThat(event.getTopic()).doesNotContain("in_app");
            assertThat(event.getEventType()).isEqualTo("NOTIFICATION_RETRY_DUE");
            assertThat(event.getPayload()).contains("\"eventType\": \"NOTIFICATION_RETRY_DUE\"");

            // Assert next_retry_at is cleared on RETRYING -> QUEUED
            NotificationEntity reloaded = notificationRepository.findById(saved.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.QUEUED);
            assertThat(reloaded.getNextRetryAt()).isNull();
            assertThat(reloaded.getDispatchGeneration()).isEqualTo(2);
        }
    }
}
