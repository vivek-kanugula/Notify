package com.notify.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractKafkaIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.common.model.OutboxStatus;
import com.notify.kafka.producer.KafkaDispatchProducer;
import com.notify.notification.dto.CreateNotificationRequest;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.service.NotificationService;
import com.notify.outbox.entity.OutboxEventEntity;
import com.notify.outbox.repository.OutboxEventRepository;
import com.notify.outbox.service.OutboxRelayService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationScheduledDispatchE2ETest extends AbstractKafkaIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private KafkaDispatchProducer kafkaDispatchProducer;

    @Autowired
    private org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private Clock clock;

    @Autowired(required = false)
    private com.notify.kafka.config.KafkaTopicConfigReconciler topicReconciler;

    private OutboxRelayService outboxRelayService;
    private KafkaConsumer<String, String> consumer;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        if (topicReconciler != null) {
            topicReconciler.reconcileNow();
        }

        outboxEventRepository.deleteAll();

        TenantEntity tenant = new TenantEntity("DispatchE2EOrg", "dispatch-e2e-" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);
        this.tenantId = tenant.getId();

        outboxRelayService = new OutboxRelayService(
                outboxEventRepository, kafkaDispatchProducer, clock, transactionManager
        );

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dispatch-e2e-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList("notify.dispatch.email.v1"));

        // Warm up Kafka producer network connection and metadata on contracted topic so cold first-send does not time out
        try {
            kafkaTemplate.partitionsFor("notify.dispatch.email.v1");
        } catch (Exception ignored) {
        }
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    @DisplayName("Ingress acceptance isolation: notification acceptance succeeds 202 and commits to DB and outbox atomically without calling outbox relay or Kafka")
    void ingressAcceptance_persistsAtomicallyWithoutSynchronousRelay() {
        CreateNotificationRequest request = new CreateNotificationRequest(
                Channel.EMAIL,
                "ingress@example.com",
                "Ingress Availability Subject",
                "Ingress Availability Body",
                null,
                null,
                null,
                null,
                null,
                null
        );

        NotificationService.AcceptanceResult result = notificationService.acceptNotification(tenantId, request, null);
        assertThat(result.status()).isEqualTo(NotificationStatus.QUEUED);

        NotificationEntity entity = notificationRepository.findById(result.notificationId()).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(NotificationStatus.QUEUED);

        List<OutboxEventEntity> outboxList = outboxEventRepository.findByTenantIdAndStatus(tenantId, OutboxStatus.PENDING);
        assertThat(outboxList).hasSize(1);
        assertThat(outboxList.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outboxList.get(0).getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("Live Kafka dispatch flow: OutboxRelay publishes to Testcontainers broker with notificationId key, tenantId header, and 1-indexed attempt")
    void liveDispatch_publishesToKafka_withCanonicalEnvelopeAndHeader() throws Exception {
        CreateNotificationRequest request = new CreateNotificationRequest(
                Channel.EMAIL,
                "live@example.com",
                "Live Dispatch Subject",
                "Live Dispatch Body",
                null,
                null,
                null,
                null,
                null,
                null
        );

        NotificationService.AcceptanceResult result = notificationService.acceptNotification(tenantId, request, null);
        UUID notificationId = result.notificationId();

        // Relay processes batch and publishes to real Kafka
        int published = outboxRelayService.processBatch();
        assertThat(published).isEqualTo(1);

        // Verify outbox row marked PUBLISHED in database
        List<OutboxEventEntity> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        OutboxEventEntity outbox = events.get(0);
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(outbox.getPublishedAt()).isNotNull();

        // Consume message from Testcontainers Kafka broker (address supplied by AbstractKafkaIntegrationTest)
        ConsumerRecord<String, String> received = null;
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline) && received == null) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> rec : records) {
                if (rec.key().equals(notificationId.toString())) {
                    received = rec;
                    break;
                }
            }
        }

        assertThat(received).isNotNull();
        assertThat(received.key()).isEqualTo(notificationId.toString());

        // Verify tenantId header
        var tenantHeader = received.headers().lastHeader("tenantId");
        assertThat(tenantHeader).isNotNull();
        assertThat(new String(tenantHeader.value(), StandardCharsets.UTF_8)).isEqualTo(tenantId.toString());

        // Verify canonical envelope JSON
        JsonNode json = objectMapper.readTree(received.value());
        assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.get("eventType").asText()).isEqualTo("NOTIFICATION_QUEUED");
        assertThat(json.get("notificationId").asText()).isEqualTo(notificationId.toString());
        assertThat(json.get("tenantId").asText()).isEqualTo(tenantId.toString());
        assertThat(json.get("channel").asText()).isEqualTo("EMAIL");
        assertThat(json.get("generation").asInt()).isEqualTo(1);
        assertThat(json.get("attempt").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Scheduled release to live Kafka flow: release bumps generation, enqueues outbox, and publishes to Kafka")
    void scheduledRelease_toLiveKafka() throws Exception {
        Instant scheduledAt = Instant.now(clock).plus(1, ChronoUnit.HOURS);
        CreateNotificationRequest request = new CreateNotificationRequest(
                Channel.EMAIL,
                "sched-live@example.com",
                "Scheduled Live Subject",
                "Scheduled Live Body",
                null,
                null,
                null,
                null,
                null,
                scheduledAt
        );

        NotificationService.AcceptanceResult result = notificationService.acceptNotification(tenantId, request, null);
        UUID notificationId = result.notificationId();

        // 1. Early release attempt before scheduled time: returns false due to scheduledAt <= now fence
        boolean earlyReleased = notificationService.releaseScheduledNotification(tenantId, notificationId, 0);
        assertThat(earlyReleased).isFalse();

        // 2. Advance time: update scheduled_at to past so it is due
        NotificationEntity entity = notificationRepository.findById(notificationId).orElseThrow();
        entity.setScheduledAt(Instant.now(clock).minus(5, ChronoUnit.SECONDS));
        notificationRepository.save(entity);

        // 3. Release scheduled notification when due
        boolean released = notificationService.releaseScheduledNotification(tenantId, notificationId, 0);
        assertThat(released).isTrue();

        // Relay processes batch to Kafka
        int published = outboxRelayService.processBatch();
        assertThat(published).isEqualTo(1);

        // Consume from Kafka
        ConsumerRecord<String, String> received = null;
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline) && received == null) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> rec : records) {
                if (rec.key().equals(notificationId.toString())) {
                    received = rec;
                    break;
                }
            }
        }

        assertThat(received).isNotNull();
        JsonNode json = objectMapper.readTree(received.value());
        assertThat(json.get("notificationId").asText()).isEqualTo(notificationId.toString());
        assertThat(json.get("generation").asInt()).isEqualTo(1);
        assertThat(json.get("attempt").asInt()).isEqualTo(1);
    }
}
