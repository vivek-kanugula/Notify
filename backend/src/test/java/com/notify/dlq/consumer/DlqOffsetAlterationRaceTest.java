package com.notify.dlq.consumer;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * <b>Executed broker evidence</b> for the init-versus-listener race protection (/ , requirement 3).
 *
 * <p>Why this exists separately from {@code DlqOffsetInitializerTest.refusesAnActiveGroup}: that case proves only
 * that the initializer's own {@code requireInactiveGroup} precheck refuses. It never reaches the broker, so it says
 * nothing about what the coordinator does if a write is attempted anyway - and a read-then-write precheck alone
 * cannot establish the protection, because a group could in principle become active between the check and the
 * write. Client API inspection cannot establish it either: that only shows the call exists.
 *
 * <p>So this test does what nothing else here does. It starts a <b>real</b> group member, waits for confirmed
 * membership <em>with an assignment</em>, bypasses the precheck by calling
 * {@code Admin.alterConsumerGroupOffsets} directly, and asserts the broker rejects it and leaves the committed
 * offset exactly as it was. Membership is re-asserted after the attempt, so a rejection cannot be mistaken for a
 * member that had already left, and a timeout or a disconnect is explicitly excluded.
 *
 * <p>Environment recorded with the result: broker image {@code apache/kafka:3.8.0} - this suite's own disposable
 * container - and {@code kafka-clients 4.1.2} as resolved by the build. The observed error is asserted and logged,
 * never assumed.
 */
class DlqOffsetAlterationRaceTest {

    private static final Logger log = LoggerFactory.getLogger(DlqOffsetAlterationRaceTest.class);
    private static final String BROKER_IMAGE = "apache/kafka:3.8.0";

    private static KafkaContainer kafka;
    private static Admin admin;

    @BeforeAll
    static void startKafka() {
        kafka = new KafkaContainer(DockerImageName.parse(BROKER_IMAGE));
        kafka.start();
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 20_000);
        admin = Admin.create(properties);
    }

    @AfterAll
    static void stopKafka() {
        if (admin != null) {
            admin.close(Duration.ofSeconds(5));
        }
        if (kafka != null) {
            kafka.stop();
        }
    }

    @Test
    @DisplayName("(3) the broker rejects an admin offset alteration while the group has a live member")
    void theBrokerRejectsAnAlterationForAnActiveGroup() throws Exception {
        String topic = "n17-race-" + UUID.randomUUID();
        String groupId = "n17-race-group-" + UUID.randomUUID();
        TopicPartition partition = new TopicPartition(topic, 0);
        admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
        produce(topic, 5);

        // A position that must survive the attempt, so "unchanged" is observable rather than "absent".
        admin.alterConsumerGroupOffsets(groupId, Map.of(partition, new OffsetAndMetadata(2L))).all().get();
        assertThat(committed(groupId, partition)).isEqualTo(2L);

        AtomicBoolean polling = new AtomicBoolean(true);
        Thread member = startMember(topic, groupId, polling);
        try {
            await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(500)).until(() -> {
                ConsumerGroupDescription description =
                        admin.describeConsumerGroups(List.of(groupId)).all().get().get(groupId);
                return description != null
                        && !description.members().isEmpty()
                        && description.members().stream()
                                .anyMatch(m -> !m.assignment().topicPartitions().isEmpty());
            });

            // The precheck is deliberately not used: this is the write the coordinator must refuse.
            Throwable rejection = assertThatThrownBy(() -> admin
                    .alterConsumerGroupOffsets(groupId, Map.of(partition, new OffsetAndMetadata(0L)))
                    .all().get())
                    .as("the broker, not a local check, is what makes this write impossible under a live member")
                    .isInstanceOf(ExecutionException.class)
                    .actual();
            Throwable cause = rejection.getCause();
            log.info("Broker {} with kafka-clients 4.1.2 rejected alterConsumerGroupOffsets for an active group: "
                    + "{}: {}", BROKER_IMAGE, cause.getClass().getName(), cause.getMessage());

            assertThat(cause)
                    .as("a coordinator/protocol rejection, not a timeout and not a disconnect")
                    .isInstanceOf(org.apache.kafka.common.errors.ApiException.class)
                    .isNotInstanceOf(org.apache.kafka.common.errors.TimeoutException.class)
                    .isNotInstanceOf(org.apache.kafka.common.errors.DisconnectException.class);

            assertThat(committed(groupId, partition))
                    .as("and the committed offset is untouched by the refused write")
                    .isEqualTo(2L);

            ConsumerGroupDescription after =
                    admin.describeConsumerGroups(List.of(groupId)).all().get().get(groupId);
            assertThat(after.members())
                    .as("membership was still live when the rejection happened, so this is the real race case")
                    .isNotEmpty();
        } finally {
            polling.set(false);
            member.join(Duration.ofSeconds(30).toMillis());
        }
    }

    private Thread startMember(String topic, String groupId, AtomicBoolean polling) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        Thread member = new Thread(() -> {
            try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties)) {
                consumer.subscribe(List.of(topic));
                while (polling.get()) {
                    consumer.poll(Duration.ofMillis(200));
                }
            }
        }, "n17-race-member");
        member.setDaemon(true);
        member.start();
        return member;
    }

    private void produce(String topic, int records) throws Exception {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(properties)) {
            for (int i = 0; i < records; i++) {
                producer.send(new ProducerRecord<>(topic, "k" + i, ("v" + i).getBytes())).get();
            }
        }
    }

    private long committed(String groupId, TopicPartition partition) throws Exception {
        OffsetAndMetadata offset = admin.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata().get().get(partition);
        return offset == null ? -1L : offset.offset();
    }
}
