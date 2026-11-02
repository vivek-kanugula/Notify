package com.notify.kafka;

import com.notify.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that in non-Kafka test contexts (which use application-test.yml with spring.kafka.bootstrap-servers=""),
 * the explicit KafkaAdmin bean, topic provisioning beans, and KafkaTopicConfigReconciler are NOT active,
 * ensuring tests never attempt to connect to, create, or alter topics on a developer-managed localhost:9092 broker.
 */
class KafkaContextIsolationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("Isolation: KafkaAdmin and KafkaTopicConfigReconciler are NOT loaded in non-Kafka test context")
    void nonKafkaTestContext_doesNotLoadKafkaAdminOrReconciler() {
        assertThat(applicationContext.containsBean("kafkaAdmin"))
                .as("kafkaAdmin bean must NOT be present in non-Kafka test context")
                .isFalse();

        assertThat(applicationContext.containsBean("kafkaTopicConfigReconciler"))
                .as("kafkaTopicConfigReconciler must NOT be present in non-Kafka test context")
                .isFalse();

        assertThat(applicationContext.containsBean("dispatchEmailTopic"))
                .as("Topic provisioning beans must NOT be present in non-Kafka test context")
                .isFalse();

        assertThat(applicationContext.containsBean("dispatchInappTopic"))
                .as("dispatchInappTopic must NOT be present in non-Kafka test context")
                .isFalse();
    }
}
