package com.notify;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared singleton Kafka 3.8 Testcontainers base.
 * Inherits PostgreSQL singleton container and adds Kafka container,
 * providing dynamic bootstrap servers and database properties for integration tests.
 */
public abstract class AbstractKafkaIntegrationTest extends AbstractPostgresIntegrationTest {

    protected static final KafkaContainer kafka;

    static {
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));
        kafka.start();
    }

    @DynamicPropertySource
    static void configureKafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
}
