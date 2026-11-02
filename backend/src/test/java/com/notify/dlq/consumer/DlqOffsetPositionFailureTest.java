package com.notify.dlq.consumer;

import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.dlq.config.DlqConsumerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * What a dead-letter listener does with each possible offset position, against a <b>disposable</b> Kafka of its own
 * (/ , , ; to ).
 *
 * <p>The listener is built from the <b>real</b> consumer configuration ({@link DlqConsumerKafkaConfig#
 * consumerProperties}, so {@code auto.offset.reset=none} comes from production code, not from the test) and the
 * <b>real</b> failure policy ({@link DlqConsumerRecoveryPolicy}). Only the surroundings are local: this suite owns
 * its container, and every topic and group is generated per test, so no shared group, topic or position is touched.
 * No seek, reset or backlog drainage is performed on any shared Kafka.
 *
 * <p>The four positions and the required behaviour:
 * <ul>
 *   <li><b></b> a valid committed offset - the listener resumes from it and reads nothing before it;</li>
 *   <li><b></b> no committed offset - the listener <b>stops</b>. It does not seek, it does not consume and
 *       it commits nothing, so an operator decides what the position should be;</li>
 *   <li><b></b> an out-of-range committed offset - the same stop, and the stored offset is left exactly as
 *       it was: repairing it is separately authorized recovery, not something a restart does quietly;</li>
 *   <li><b></b> a partition with no position inside an otherwise initialized topic - identical to
 *       . "A new partition" is not inferred and is not treated as a licence to seek.</li>
 * </ul>
 */
class DlqOffsetPositionFailureTest {

    private static KafkaContainer kafka;
    private static Admin admin;

    private static final DlqConsumerProperties PROPS = new DlqConsumerProperties(
            new DlqConsumerProperties.Consumer(
                    true, "unused", "unused-topic", 10, 1,
                    Duration.ofMillis(200), 1, Duration.ofSeconds(1), Duration.ofSeconds(5)),
            new DlqConsumerProperties.DltConsumer(
                    false, "unused-dlt", 10, 1,
                    Duration.ofMillis(200), 1, Duration.ofSeconds(1), Duration.ofSeconds(5)),
            null,
            new DlqConsumerProperties.Api(50, 200, 20));

    @BeforeAll
    static void startKafka() {
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));
        kafka.start();
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
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

    /** Hands the controller the container under test, which is all of the registry it uses. */
    private static final class SingleContainerRegistry extends KafkaListenerEndpointRegistry {

        private final String listenerId;
        private final MessageListenerContainer container;

        private SingleContainerRegistry(String listenerId, MessageListenerContainer container) {
            this.listenerId = listenerId;
            this.container = container;
        }

        @Override
        public MessageListenerContainer getListenerContainer(String id) {
            return listenerId.equals(id) ? container : null;
        }
    }

    private record Listener(
            ConcurrentMessageListenerContainer<String, byte[]> container,
            DlqConsumerContainerController controller,
            List<String> received
    ) {
    }

    private String topicWith(int partitions, int recordsPerPartition) throws Exception {
        String topic = "n17-pos-" + UUID.randomUUID();
        admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(properties)) {
            for (int partition = 0; partition < partitions; partition++) {
                for (int i = 0; i < recordsPerPartition; i++) {
                    producer.send(new ProducerRecord<>(
                            topic, partition, "k" + partition + "-" + i,
                            ("p" + partition + "-" + i).getBytes())).get();
                }
            }
        }
        return topic;
    }

    private void commitOffset(String groupId, String topic, int partition, long offset) throws Exception {
        admin.alterConsumerGroupOffsets(
                        groupId, Map.of(new TopicPartition(topic, partition), new OffsetAndMetadata(offset)))
                .all().get();
    }

    private long committedOffset(String groupId, String topic, int partition) throws Exception {
        OffsetAndMetadata offset = admin.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata().get()
                .get(new TopicPartition(topic, partition));
        return offset == null ? -1L : offset.offset();
    }

    /**
     * Builds the listener from production configuration: the consumer properties and the error handler are the ones
     * the application uses, so {@code auto.offset.reset=none} and the failure policy are not restated here.
     */
    private Listener listenerFor(String topic, String groupId) {
        Map<String, Object> config = DlqConsumerKafkaConfig.consumerProperties(kafka.getBootstrapServers(), PROPS);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG, groupId);
        ConsumerFactory<String, byte[]> consumerFactory = new DefaultKafkaConsumerFactory<>(config);

        List<String> received = new CopyOnWriteArrayList<>();
        ContainerProperties containerProperties = new ContainerProperties(topic);
        containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        containerProperties.setGroupId(groupId);
        containerProperties.setMessageListener(
                (AcknowledgingMessageListener<String, byte[]>) (ConsumerRecord<String, byte[]> record,
                        org.springframework.kafka.support.Acknowledgment acknowledgment) -> {
                    received.add(record.partition() + "@" + record.offset());
                    if (acknowledgment != null) {
                        acknowledgment.acknowledge();
                    }
                });

        ConcurrentMessageListenerContainer<String, byte[]> container =
                new ConcurrentMessageListenerContainer<>(consumerFactory, containerProperties);
        container.setBeanName(DlqEventConsumer.LISTENER_ID);
        container.setAutoStartup(false);

        DlqConsumerContainerController controller = new DlqConsumerContainerController(
                new SingleContainerRegistry(DlqEventConsumer.LISTENER_ID, container),
                DlqEventConsumer.LISTENER_ID,
                PROPS.consumer().pauseInitial(),
                PROPS.consumer().pauseMax(),
                new SimpleMeterRegistry());
        container.setCommonErrorHandler(DlqConsumerRecoveryPolicy.errorHandler(
                new DeliveryExceptionClassifier(), controller, PROPS.consumer()));
        // The same position gate the container factory installs in production.
        container.getContainerProperties().setConsumerRebalanceListener(new DlqPositionGate(controller, groupId));
        return new Listener(container, controller, received);
    }

    @Test
    @DisplayName("a valid committed offset is resumed, and nothing before it is read again")
    void resumesAValidCommittedOffset() throws Exception {
        String topic = topicWith(1, 4);
        String groupId = "n17-pos-group-" + UUID.randomUUID();
        commitOffset(groupId, topic, 0, 2L);
        Listener listener = listenerFor(topic, groupId);

        listener.controller().start("test: valid committed offset");
        try {
            await().atMost(Duration.ofSeconds(30)).until(() -> listener.received().size() == 2);
            assertThat(listener.received())
                    .as("offsets 0 and 1 were already consumed, so only 2 and 3 arrive")
                    .containsExactly("0@2", "0@3");
            assertThat(listener.container().isRunning())
                    .as("a resumable position keeps the listener running")
                    .isTrue();
        } finally {
            listener.container().stop();
        }
    }

    @Test
    @DisplayName("no committed offset stops the listener, consumes nothing and commits nothing")
    void stopsWhenNoOffsetIsCommitted() throws Exception {
        String topic = topicWith(1, 3);
        String groupId = "n17-pos-group-" + UUID.randomUUID();
        Listener listener = listenerFor(topic, groupId);

        listener.controller().start("test: no committed offset");
        try {
            // The recorded reason first: a plain "not running" check could pass before the container ever started.
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
                    .until(() -> listener.controller().lastStopReason() != null);
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
                    .until(() -> !listener.container().isRunning());
            assertThat(listener.received())
                    .as("auto.offset.reset=none means an unresolved position reads nothing at all")
                    .isEmpty();
            assertThat(committedOffset(groupId, topic, 0))
                    .as("and commits nothing, so the position stays an operator decision")
                    .isEqualTo(-1L);
            assertThat(listener.controller().lastStopReason())
                    .as("the stop reason names the unresolved position, the partition and the group")
                    .contains("UNRESOLVED_OFFSET_POSITION")
                    .contains(topic)
                    .contains(groupId);
        } finally {
            listener.container().stop();
        }
    }

    @Test
    @DisplayName("an out-of-range committed offset stops the listener and is left exactly as stored")
    void stopsWhenTheCommittedOffsetIsOutOfRange() throws Exception {
        String topic = topicWith(1, 3);
        String groupId = "n17-pos-group-" + UUID.randomUUID();
        commitOffset(groupId, topic, 0, 99L);
        Listener listener = listenerFor(topic, groupId);

        listener.controller().start("test: out-of-range committed offset");
        try {
            // The recorded reason first: a plain "not running" check could pass before the container ever started.
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
                    .until(() -> listener.controller().lastStopReason() != null);
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
                    .until(() -> !listener.container().isRunning());
            assertThat(listener.received()).isEmpty();
            assertThat(committedOffset(groupId, topic, 0))
                    .as("no silent reset to earliest or latest: repair is separately authorized")
                    .isEqualTo(99L);
            assertThat(listener.controller().lastStopReason())
                    .as("an out-of-range position is reported as unresolved, not retried forever, and CONTRACTS "
                            + "requires the committed offset and the current retained range in the message")
                    .contains("UNRESOLVED_OFFSET_POSITION")
                    .contains("OffsetOutOfRangeException")
                    .contains(groupId)
                    .contains(topic + "-0 committed at 99")
                    .contains("retained range [0, 3]");
        } finally {
            listener.container().stop();
        }
    }

    @Test
    @DisplayName("a partition with no position behaves exactly like an absent offset, never like new")
    void treatsAnUninitializedPartitionLikeAnAbsentOffset() throws Exception {
        String topic = topicWith(2, 2);
        String groupId = "n17-pos-group-" + UUID.randomUUID();
        // Partition 0 is initialized; partition 1 deliberately is not.
        commitOffset(groupId, topic, 0, 1L);
        Listener listener = listenerFor(topic, groupId);

        listener.controller().start("test: one partition without a position");
        try {
            // The recorded reason first: a plain "not running" check could pass before the container ever started.
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
                    .until(() -> listener.controller().lastStopReason() != null);
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
                    .until(() -> !listener.container().isRunning());
            assertThat(listener.received())
                    .as("no record from the uninitialized partition is ever delivered")
                    .noneMatch(entry -> entry.startsWith("1@"));
            assertThat(committedOffset(groupId, topic, 1))
                    .as("the uninitialized partition is still uninitialized: nothing was inferred for it")
                    .isEqualTo(-1L);
            assertThat(committedOffset(groupId, topic, 0))
                    .as("and the initialized partition keeps a position of its own")
                    .isGreaterThanOrEqualTo(1L);
        } finally {
            listener.container().stop();
        }
    }
}
