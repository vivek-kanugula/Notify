package com.notify.dlq.consumer;

import com.notify.AbstractIsolatedDatabaseTest;
import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.common.model.DlqStatus;
import com.notify.common.model.NotificationStatus;
import com.notify.dlq.entity.DeadLetterEntryEntity;
import com.notify.dlq.repository.DeadLetterEntryRepository;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The authoritative consumer, end to end on disposable infrastructure (/ , ; , ).
 *
 * <p>This is the only suite that runs the whole ingest path in one piece: a real {@code notify.dlq.v1} record, the
 * real offset initializer reading a real input file, the real startup gate, the real listener and the real
 * materialization transaction.
 *
 * <p><b>The claim under test is the acknowledgement order.</b> The offset is committed only after the
 * materialization transaction has committed, so a crash between the two can only repeat a record - and a repeat is
 * a DUPLICATE, never a second entry. Proof: after the entry exists, the committed offset has moved past the
 * record; and a second delivery of the same event changes nothing while still advancing the offset.
 *
 * <p>Everything here is fixture-owned: the topic, the consumer group, the offset-initialization file and the
 * tenant. The group is created by this suite and initialized only on this disposable Kafka. No shared group, topic,
 * offset or backlog is touched.
 */
@TestPropertySource(properties = {
        "notify.dlq.consumer.enabled=true",
        "notify.dlq.dlt-consumer.enabled=false",
        "notify.worker.email.enabled=false",
        "notify.worker.webhook.enabled=false",
        "notify.worker.inapp.enabled=false",
        "notify.recovery.enabled=false",
        "notify.reconciler.enabled=false",
        "notify.outbox.relay-enabled=false"
})
class DlqConsumerAckOrderIntegrationTest extends AbstractIsolatedDatabaseTest {

    private static final String GROUP_ID = "n17-ack-group-" + UUID.randomUUID();
    /**
     * A topic of this suite's own. The broker is shared by every { AbstractIsolatedDatabaseTest} subclass in
     * the JVM, so using the contracted { notify.dlq.v1} would mean consuming another suite's dead-letter
     * events and quarantining rows that belong to it. The consumer topic is a property for exactly this reason.
     */
    private static final String TOPIC = "notify.test.dlq.ack." + UUID.randomUUID();
    private static final int PARTITIONS = 1;

    private static Path offsetInitFile;

    /**
     * Runs before the context: the topic must exist and the group must have a reviewed starting position, because
     * the gate refuses to start a listener whose position is unresolved. This is the ordinary first-deployment
     * sequence, executed against this suite's own Kafka.
     */
    static {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, isolatedKafka.getBootstrapServers());
        try (Admin admin = Admin.create(properties)) {
            admin.createTopics(List.of(new NewTopic(TOPIC, PARTITIONS, (short) 1))).all().get();
            offsetInitFile = Files.createTempFile("n17-offsets-", ".json");
            Files.writeString(offsetInitFile, "{\"environment\":\"test\",\"groupId\":\"" + GROUP_ID + "\","
                    + "\"offsets\":[{\"topic\":\"" + TOPIC
                    + "\",\"partition\":0,\"nextOffset\":0}]}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (IOException | java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void consumerConfiguration(DynamicPropertyRegistry registry) {
        registry.add("notify.dlq.consumer.group-id", () -> GROUP_ID);
        registry.add("notify.dlq.consumer.topic", () -> TOPIC);
        registry.add("notify.dlq.offset-init-file", () -> offsetInitFile.toString());
    }

    @Autowired private DeadLetterEntryRepository entries;
    @Autowired private TenantRepository tenants;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired private DlqConsumerStartupGate gate;

    private UUID tenantId;
    private Admin admin;
    private KafkaProducer<String, byte[]> producer;

    @BeforeEach
    void setUp() {
        tenantId = tenants.save(new TenantEntity("AckOrg", "ack-" + UUID.randomUUID())).getId();

        Properties adminProperties = new Properties();
        adminProperties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, isolatedKafka.getBootstrapServers());
        admin = Admin.create(adminProperties);

        Properties producerProperties = new Properties();
        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, isolatedKafka.getBootstrapServers());
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producerProperties.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(producerProperties);
    }

    @AfterEach
    void tearDown() {
        if (producer != null) {
            producer.close(Duration.ofSeconds(5));
        }
        if (admin != null) {
            admin.close(Duration.ofSeconds(5));
        }
        jdbc.update("DELETE FROM dlq_quarantine WHERE topic = ?", TOPIC);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    private UUID seedFailedNotification(int cycle, int generation) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, 'EMAIL', 'FAILED', 'someone@example.com', 'subject', 'body', ?, ?, 5, 5, now())
                """, id, tenantId, generation, cycle);
        return id;
    }

    private String eventJson(UUID notificationId, int cycle, int generation) {
        return "{\"schemaVersion\":1,\"eventId\":\"" + UUID.randomUUID() + "\","
                + "\"eventType\":\"NOTIFICATION_DEAD_LETTERED\","
                + "\"notificationId\":\"" + notificationId + "\",\"tenantId\":\"" + tenantId + "\","
                + "\"channel\":\"" + Channel.EMAIL + "\",\"generation\":" + generation
                + ",\"deliveryCycle\":" + cycle + ",\"reason\":\"" + DlqReason.MAX_RETRIES_EXCEEDED + "\","
                + "\"lastErrorCode\":\"PROVIDER_UNAVAILABLE\",\"attemptCount\":5,"
                + "\"occurredAt\":\"2026-10-17T10:00:00Z\"}";
    }

    private RecordMetadata send(UUID notificationId, int cycle, int generation) throws Exception {
        return producer.send(new ProducerRecord<>(
                TOPIC, notificationId.toString(),
                eventJson(notificationId, cycle, generation).getBytes(StandardCharsets.UTF_8))).get();
    }

    private long committedOffset() throws Exception {
        Map<TopicPartition, OffsetAndMetadata> all =
                admin.listConsumerGroupOffsets(GROUP_ID).partitionsToOffsetAndMetadata().get();
        OffsetAndMetadata offset = all.get(new TopicPartition(TOPIC, 0));
        return offset == null ? -1L : offset.offset();
    }

    private long replayCountOf(UUID notificationId) {
        Long value = jdbc.queryForObject(
                "SELECT replay_count FROM dead_letter_entries WHERE notification_id = ?", Long.class,
                notificationId);
        return value == null ? -1 : value;
    }

    @Test
    @DisplayName("the initializer ran, committed the reviewed position and the listener is consuming")
    void theListenerStartedFromTheReviewedPosition() {
        assertThat(gate.refusals()).isEmpty();
        assertThat(gate.applied()).anySatisfy(entry -> assertThat(entry).contains(DlqEventConsumer.LISTENER_ID));
        assertThat(registry.getListenerContainer(DlqEventConsumer.LISTENER_ID).isRunning())
                .as("a resolved position is the only state in which a dead-letter listener runs")
                .isTrue();
    }

    @Test
    @DisplayName("the offset is committed only after the materialization transaction, so a crash repeats")
    void acknowledgesOnlyAfterTheTransactionCommits() throws Exception {
        UUID notificationId = seedFailedNotification(1, 0);

        RecordMetadata metadata = send(notificationId, 1, 0);

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
                .until(() -> entries.findByNotificationId(notificationId).isPresent());
        DeadLetterEntryEntity entry = entries.findByNotificationId(notificationId).orElseThrow();
        assertThat(entry.getStatus()).isEqualTo(DlqStatus.OPEN);
        assertThat(jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, notificationId))
                .isEqualTo(NotificationStatus.DLQ.name());

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
                .until(() -> committedOffset() > metadata.offset());
        assertThat(committedOffset())
                .as("the acknowledgement follows the durable write, never precedes it")
                .isEqualTo(metadata.offset() + 1);
    }

    @Test
    @DisplayName("a repeated record is a DUPLICATE: one entry, no second transition, still acknowledged")
    void aRepeatedRecordChangesNothing() throws Exception {
        UUID notificationId = seedFailedNotification(1, 0);
        RecordMetadata first = send(notificationId, 1, 0);
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
                .until(() -> entries.findByNotificationId(notificationId).isPresent()
                        && committedOffset() > first.offset());
        long replayCountBefore = replayCountOf(notificationId);
        long historyBefore = historyCount(notificationId);

        RecordMetadata second = send(notificationId, 1, 0);

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
                .until(() -> committedOffset() > second.offset());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM dead_letter_entries WHERE notification_id = ?", Long.class, notificationId))
                .as("the second delivery of the same verdict creates no second entry")
                .isEqualTo(1L);
        assertThat(replayCountOf(notificationId)).isEqualTo(replayCountBefore);
        assertThat(historyCount(notificationId))
                .as("and writes no second history row")
                .isEqualTo(historyBefore);
    }

    private long historyCount(UUID notificationId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM notification_status_history WHERE notification_id = ?", Long.class,
                notificationId);
        return count == null ? 0 : count;
    }
}
