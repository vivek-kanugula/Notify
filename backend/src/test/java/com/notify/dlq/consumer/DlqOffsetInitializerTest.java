package com.notify.dlq.consumer;

import com.notify.dlq.consumer.DlqOffsetInitializer.InitializationRefusedException;
import com.notify.dlq.consumer.OffsetInitializationInput.PartitionOffset;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
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
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The offset initializer, against a <b>disposable</b> Kafka of its own (/ , , ; ).
 *
 * <p>This suite owns its container, and every topic and group it names is generated per test, so it cannot touch a
 * shared group, a shared topic or a shared position. That restriction is the point: initialization, seek, reset and
 * backlog drainage on any shared Kafka are separately authorized actions and are not performed here.
 *
 * <p>No Spring context is started. The initializer takes an {@link Admin} and an environment string, so the unit
 * under test is exactly the validation and commit logic.
 *
 * <p>What is proved: the input must match the environment and the group; entries must be well formed; the topic and
 * partition must exist and the offset must be inside the retained range; <b>an existing committed position is never
 * overwritten</b>; a re-run of the same input is a no-op; and an active group is refused.
 */
class DlqOffsetInitializerTest {

    private static final String ENVIRONMENT = "test";

    private static KafkaContainer kafka;
    private static Admin admin;

    @BeforeAll
    static void startKafka() {
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));
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

    private DlqOffsetInitializer initializer() {
        return new DlqOffsetInitializer(admin, ENVIRONMENT);
    }

    /** A fixture-owned topic with one partition holding {@code records} records. */
    private String topicWith(int records) throws Exception {
        String topic = "n17-init-" + UUID.randomUUID();
        admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(properties)) {
            for (int i = 0; i < records; i++) {
                producer.send(new ProducerRecord<>(topic, "k" + i, ("v" + i).getBytes())).get();
            }
        }
        return topic;
    }

    private String group() {
        return "n17-init-group-" + UUID.randomUUID();
    }

    private OffsetInitializationInput input(String groupId, String topic, long nextOffset) {
        return new OffsetInitializationInput(
                ENVIRONMENT, groupId, List.of(new PartitionOffset(topic, 0, nextOffset)));
    }

    private long committed(String groupId, String topic) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> all =
                admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get();
        OffsetAndMetadata offset = all.get(new TopicPartition(topic, 0));
        return offset == null ? -1L : offset.offset();
    }

    private void commitOffset(String groupId, String topic, int partition, long offset) throws Exception {
        admin.alterConsumerGroupOffsets(groupId,
                        Map.of(new TopicPartition(topic, partition), new OffsetAndMetadata(offset)))
                .all().get();
    }

    private long committedOffsetOf(String groupId, String topic, int partition) throws Exception {
        OffsetAndMetadata offset = admin.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata().get()
                .get(new TopicPartition(topic, partition));
        return offset == null ? -1L : offset.offset();
    }

    @Test
    @DisplayName("a matching input initializes only the partitions it lists, to the exact offsets")
    void initializesExactOffsets() throws Exception {
        String topic = topicWith(5);
        String groupId = group();

        DlqOffsetInitializer.Result result = initializer().apply(input(groupId, topic, 3L), groupId, Set.of(topic));

        assertThat(result.committed()).isEqualTo(1);
        assertThat(result.skippedExisting()).isZero();
        assertThat(committed(groupId, topic)).isEqualTo(3L);
    }

    @Test
    @DisplayName("re-running the same input commits nothing and changes no position")
    void reRunIsANoOp() throws Exception {
        String topic = topicWith(5);
        String groupId = group();
        DlqOffsetInitializer initializer = initializer();
        initializer.apply(input(groupId, topic, 2L), groupId, Set.of(topic));

        DlqOffsetInitializer.Result second = initializer.apply(input(groupId, topic, 2L), groupId, Set.of(topic));

        assertThat(second.committed()).isZero();
        assertThat(second.skippedExisting()).isEqualTo(1);
        assertThat(second.notes()).anySatisfy(note -> assertThat(note).contains("left untouched"));
        assertThat(committed(groupId, topic)).isEqualTo(2L);
    }

    @Test
    @DisplayName("an existing committed position survives an input that names a different one")
    void neverOverwritesAnExistingPosition() throws Exception {
        String topic = topicWith(5);
        String groupId = group();
        admin.alterConsumerGroupOffsets(groupId, Map.of(new TopicPartition(topic, 0), new OffsetAndMetadata(4L)))
                .all().get();

        DlqOffsetInitializer.Result result = initializer().apply(input(groupId, topic, 1L), groupId, Set.of(topic));

        assertThat(result.committed())
                .as("the input wanted offset 1, but a valid position already existed")
                .isZero();
        assertThat(result.skippedExisting()).isEqualTo(1);
        assertThat(committed(groupId, topic))
                .as("requirement 2 of preserve every existing committed offset")
                .isEqualTo(4L);
    }

    @Test
    @DisplayName("an offset past the end of the partition is refused and nothing is committed")
    void refusesAnOffsetOutsideTheRetainedRange() throws Exception {
        String topic = topicWith(3);
        String groupId = group();

        assertThatThrownBy(() -> initializer().apply(input(groupId, topic, 99L), groupId, Set.of(topic)))
                .isInstanceOf(InitializationRefusedException.class)
                .hasMessageContaining("outside the retained range");
        assertThat(committed(groupId, topic)).isEqualTo(-1L);
    }

    @Test
    @DisplayName("a topic that does not exist is refused, not created")
    void refusesAnUnknownTopic() throws Exception {
        String groupId = group();
        String absent = "n17-init-absent-" + UUID.randomUUID();

        assertThatThrownBy(() -> initializer().apply(input(groupId, absent, 0L), groupId, Set.of(absent)))
                .isInstanceOf(InitializationRefusedException.class);
        assertThat(admin.listTopics().names().get())
                .as("validation must not create the topic it was asked about")
                .doesNotContain(absent);
    }

    @Test
    @DisplayName("an input for another environment or another group is refused and commits nothing")
    void refusesAMismatchedInput() throws Exception {
        String topic = topicWith(2);
        String groupId = group();

        assertThatThrownBy(() -> initializer().apply(
                new OffsetInitializationInput("prod", groupId, List.of(new PartitionOffset(topic, 0, 0L))),
                groupId, Set.of(topic)))
                .isInstanceOf(InitializationRefusedException.class)
                .hasMessageContaining("does not match the running environment");

        assertThatThrownBy(() -> initializer().apply(input("some-other-group", topic, 0L), groupId, Set.of(topic)))
                .isInstanceOf(InitializationRefusedException.class)
                .hasMessageContaining("does not match the configured group");

        assertThat(committed(groupId, topic)).isEqualTo(-1L);
    }

    @Test
    @DisplayName("a malformed or empty input is refused")
    void refusesAMalformedInput() throws Exception {
        String topic = topicWith(2);
        String groupId = group();

        assertThatThrownBy(() -> initializer().apply(
                new OffsetInitializationInput(ENVIRONMENT, groupId, List.of()), groupId, Set.of(topic)))
                .isInstanceOf(InitializationRefusedException.class)
                .hasMessageContaining("lists no partitions");

        assertThatThrownBy(() -> initializer().apply(
                new OffsetInitializationInput(
                        ENVIRONMENT, groupId, List.of(new PartitionOffset(topic, 0, -1L))),
                groupId, Set.of(topic)))
                .isInstanceOf(InitializationRefusedException.class)
                .hasMessageContaining("invalid entry");

        assertThatThrownBy(() -> initializer().apply(
                new OffsetInitializationInput(ENVIRONMENT, groupId, null), groupId, Set.of(topic)))
                .isInstanceOf(InitializationRefusedException.class)
                .hasMessageContaining("no offsets list");
    }

    @Test
    @DisplayName("an active consumer group is refused, so no running listener can be initialized beneath")
    void refusesAnActiveGroup() throws Exception {
        String topic = topicWith(3);
        String groupId = group();
        AtomicBoolean polling = new AtomicBoolean(true);

        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        Thread member = new Thread(() -> {
            try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties)) {
                consumer.subscribe(List.of(topic));
                while (polling.get()) {
                    consumer.poll(Duration.ofMillis(200));
                }
            }
        }, "n17-active-member");
        member.setDaemon(true);
        member.start();

        try {
            // Bounded wait for the member to join, never a sleep.
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).until(() ->
                    !admin.describeConsumerGroups(List.of(groupId)).all().get().get(groupId).members().isEmpty());

            assertThatThrownBy(() -> initializer().apply(input(groupId, topic, 1L), groupId, Set.of(topic)))
                    .isInstanceOf(InitializationRefusedException.class)
                    .hasMessageContaining("initialization requires an inactive group");
        } finally {
            polling.set(false);
            member.join(Duration.ofSeconds(20).toMillis());
        }
    }

    @Test
    @DisplayName("the input file rejects an unknown field instead of ignoring it")
    void refusesAnUnknownFieldInTheFile(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("offsets.json");
        Files.writeString(file, "{\"environment\":\"test\",\"groupId\":\"g\","
                + "\"offsets\":[{\"topic\":\"t\",\"partition\":0,\"nextOffset\":1}],\"resetTo\":\"latest\"}");

        assertThatThrownBy(() -> initializer().read(file))
                .isInstanceOf(InitializationRefusedException.class)
                .hasMessageContaining("could not be read");
    }

    @Test
    @DisplayName("a well-formed file round-trips to the exact integers it records")
    void readsAWellFormedFile(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("offsets.json");
        Files.writeString(file, "{\"environment\":\"test\",\"groupId\":\"notify-dlq-consumer\","
                + "\"offsets\":[{\"topic\":\"notify.dlq.v1\",\"partition\":0,\"nextOffset\":417}]}");

        OffsetInitializationInput input = initializer().read(file);

        assertThat(input.environment()).isEqualTo("test");
        assertThat(input.groupId()).isEqualTo("notify-dlq-consumer");
        assertThat(input.offsets()).singleElement().satisfies(entry -> {
            assertThat(entry.topic()).isEqualTo("notify.dlq.v1");
            assertThat(entry.partition()).isZero();
            assertThat(entry.nextOffset()).isEqualTo(417L);
        });
    }

    /** Produces to one partition of a fixture-owned topic. */
    private void produceTo(String topic, int partition, int records) throws Exception {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(properties)) {
            for (int i = 0; i < records; i++) {
                producer.send(new ProducerRecord<>(topic, partition, "k" + i, ("v" + i).getBytes())).get();
            }
        }
    }

    /**
     * Really moves the retained range of a <b>fixture-owned</b> partition forward, by deleting records below
     * {@code before}. The validation under test is untouched: the range it reads genuinely changed.
     */
    private void deleteRecordsBelow(String topic, int partition, long before) throws Exception {
        admin.deleteRecords(Map.of(new TopicPartition(topic, partition),
                org.apache.kafka.clients.admin.RecordsToDelete.beforeOffset(before))).all().get();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).until(() ->
                admin.listOffsets(Map.of(new TopicPartition(topic, partition), OffsetSpec.earliest()))
                        .all().get().get(new TopicPartition(topic, partition)).offset() >= before);
    }

    @Test
    @DisplayName("a committed position below the retained range is skipped, and the missing partition is still "
            + "initialized")
    void anAlreadyCommittedPartitionIsSkippedEvenWhenItsRequestedOffsetFellOutOfRetention() throws Exception {
        String topic = "n17-init-" + UUID.randomUUID();
        admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1))).all().get();
        produceTo(topic, 0, 6);
        produceTo(topic, 1, 6);
        String groupId = group();
        // The state a partially completed initialization leaves behind: p0 committed, p1 not.
        commitOffset(groupId, topic, 0, 2L);
        // Retention then moves past the offset the reviewed input recorded for p0.
        deleteRecordsBelow(topic, 0, 4L);

        DlqOffsetInitializer.Result result = initializer().apply(
                new OffsetInitializationInput(ENVIRONMENT, groupId, List.of(
                        new PartitionOffset(topic, 0, 2L),
                        new PartitionOffset(topic, 1, 3L))),
                groupId, Set.of(topic));

        assertThat(result.skippedExisting())
                .as("p0 already had a position, so its recorded offset is never validated")
                .isEqualTo(1);
        assertThat(result.committed())
                .as("and the retry must still initialize the partition that genuinely needs it")
                .isEqualTo(1);
        assertThat(committedOffsetOf(groupId, topic, 0))
                .as("the existing position is preserved exactly, not repaired and not rewritten")
                .isEqualTo(2L);
        assertThat(committedOffsetOf(groupId, topic, 1)).isEqualTo(3L);
    }

    @Test
    @DisplayName("an existing committed position that is itself out of range is preserved, never called valid")
    void anOutOfRangeExistingPositionIsPreservedAndNotRepaired() throws Exception {
        String topic = "n17-init-" + UUID.randomUUID();
        admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
        produceTo(topic, 0, 6);
        String groupId = group();
        commitOffset(groupId, topic, 0, 1L);
        deleteRecordsBelow(topic, 0, 4L);          // the committed position 1 is now below earliest

        DlqOffsetInitializer.Result result = initializer().apply(
                input(groupId, topic, 5L), groupId, Set.of(topic));

        assertThat(result.committed()).isZero();
        assertThat(result.skippedExisting()).isEqualTo(1);
        assertThat(committedOffsetOf(groupId, topic, 0))
                .as("the initializer neither repairs nor overwrites it; recovery is separately authorized, and "
                        + "DlqOffsetPositionFailureTest proves the listener stops on it with the range in the reason")
                .isEqualTo(1L);
        assertThat(result.notes()).anySatisfy(note -> assertThat(note).contains("left untouched"));
    }

    @Test
    @DisplayName("an invalid requested offset on a partition that needs initialization refuses before any commit")
    void anInvalidOffsetOnANeededPartitionRefusesBeforeWriting() throws Exception {
        String topic = "n17-init-" + UUID.randomUUID();
        admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1))).all().get();
        produceTo(topic, 0, 3);
        produceTo(topic, 1, 3);
        String groupId = group();

        assertThatThrownBy(() -> initializer().apply(
                new OffsetInitializationInput(ENVIRONMENT, groupId, List.of(
                        new PartitionOffset(topic, 0, 1L),
                        new PartitionOffset(topic, 1, 99L))),
                groupId, Set.of(topic)))
                .isInstanceOf(InitializationRefusedException.class)
                .hasMessageContaining("outside the retained range");

        assertThat(committedOffsetOf(groupId, topic, 0))
                .as("the valid partition is not committed either: the range check precedes every write")
                .isEqualTo(-1L);
        assertThat(committedOffsetOf(groupId, topic, 1)).isEqualTo(-1L);
    }

    @Test
    @DisplayName("N1/N3 duplicate coordinates and an unsubscribed topic are refused without touching group offsets")
    void duplicateCoordinatesAndAnUnsubscribedTopicAreRefused() throws Exception {
        String topic = topicWith(4);
        String unrelated = topicWith(4);
        String groupId = group();

        assertThatThrownBy(() -> initializer().apply(
                new OffsetInitializationInput(ENVIRONMENT, groupId, List.of(
                        new PartitionOffset(topic, 0, 1L),
                        new PartitionOffset(topic, 0, 2L))),
                groupId, Set.of(topic)))
                .as("a conflicting duplicate must not be resolved to whichever entry came last")
                .isInstanceOf(InitializationRefusedException.class)
                .hasMessageContaining("more than once");

        assertThatThrownBy(() -> initializer().apply(
                new OffsetInitializationInput(ENVIRONMENT, groupId, List.of(
                        new PartitionOffset(topic, 0, 1L),
                        new PartitionOffset(topic, 0, 1L))),
                groupId, Set.of(topic)))
                .as("an identical repeat is refused too: reviewed data states each position once")
                .isInstanceOf(InitializationRefusedException.class)
                .hasMessageContaining("more than once");

        assertThatThrownBy(() -> initializer().apply(
                new OffsetInitializationInput(ENVIRONMENT, groupId, List.of(
                        new PartitionOffset(topic, 0, 1L),
                        new PartitionOffset(unrelated, 0, 1L))),
                groupId, Set.of(topic)))
                .isInstanceOf(InitializationRefusedException.class)
                .hasMessageContaining("is not subscribed by the listener");

        assertThat(committedOffsetOf(groupId, topic, 0)).isEqualTo(-1L);
        assertThat(committedOffsetOf(groupId, unrelated, 0)).isEqualTo(-1L);
    }
}
