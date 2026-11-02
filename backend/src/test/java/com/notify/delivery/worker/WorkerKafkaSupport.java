package com.notify.delivery.worker;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** Small Kafka helpers for the worker integration tests: topic setup, publishing, reading, offset inspection. */
final class WorkerKafkaSupport {

    private WorkerKafkaSupport() {
    }

    static void createTopics(String bootstrapServers, int partitions, String... topics) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers))) {
            List<NewTopic> newTopics = new ArrayList<>();
            for (String topic : topics) {
                newTopics.add(new NewTopic(topic, partitions, (short) 1));
            }
            admin.createTopics(newTopics).all().get();
        }
    }

    static String uniqueTopic(String prefix) {
        return prefix + "." + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Publishes raw bytes (and optional headers) and waits for the broker acknowledgment. Returns the record offset. */
    static long publish(String bootstrapServers, String topic, String key, byte[] value, Map<String, String> headers) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, value);
            headers.forEach((k, v) -> record.headers().add(new RecordHeader(k, v.getBytes(StandardCharsets.UTF_8))));
            return producer.send(record).get().offset();
        }
    }

    static long publish(String bootstrapServers, String topic, String key, String json) throws Exception {
        return publish(bootstrapServers, topic, key, json.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    /** Reads everything currently on the topic (all partitions) with a throw-away group, until idle. */
    static List<ConsumerRecord<String, byte[]>> readAll(String bootstrapServers, String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        List<ConsumerRecord<String, byte[]>> out = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(p -> new TopicPartition(topic, p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(300));
                records.forEach(out::add);
            }
        }
        return out;
    }

    /** Committed offsets of a group for one topic (empty map when the group has not committed). */
    static Map<TopicPartition, Long> committed(String bootstrapServers, String group, String topic) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers))) {
            Map<TopicPartition, OffsetAndMetadata> all = admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata().get();
            Map<TopicPartition, Long> out = new HashMap<>();
            all.forEach((tp, om) -> {
                if (tp.topic().equals(topic) && om != null) {
                    out.put(tp, om.offset());
                }
            });
            return out;
        }
    }

    /** Records on the topic that the group has not committed past (sum over partitions of end - committed). */
    static long uncommitted(String bootstrapServers, String group, String topic) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers))) {
            var description = admin.describeTopics(List.of(topic)).allTopicNames().get().get(topic);
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            description.partitions().forEach(p -> latest.put(new TopicPartition(topic, p.partition()), OffsetSpec.latest()));
            Map<TopicPartition, Long> ends = new HashMap<>();
            admin.listOffsets(latest).all().get().forEach((tp, info) -> ends.put(tp, info.offset()));
            Map<TopicPartition, Long> committed = committed(bootstrapServers, group, topic);
            long total = 0;
            for (Map.Entry<TopicPartition, Long> e : ends.entrySet()) {
                total += e.getValue() - committed.getOrDefault(e.getKey(), 0L);
            }
            return total;
        }
    }

    static String dispatchEvent(java.util.UUID tenantId, java.util.UUID notificationId, int generation, String eventType) {
        return dispatchEvent(tenantId, notificationId, generation, eventType, "EMAIL");
    }

    /** The same envelope for another channel; the outbox writes one format per channel (). */
    static String dispatchEvent(java.util.UUID tenantId, java.util.UUID notificationId, int generation,
                                String eventType, String channel) {
        return "{\"schemaVersion\":1,\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + eventType
                + "\",\"notificationId\":\"" + notificationId + "\",\"tenantId\":\"" + tenantId
                + "\",\"channel\":\"" + channel + "\",\"generation\":" + generation
                + ",\"attempt\":1,\"occurredAt\":\"" + java.time.Instant.now() + "\"}";
    }
}
