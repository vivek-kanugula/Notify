package com.notify.kafka.config;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Exposes Kafka topic reconciliation and configuration signals via Spring Boot Actuator /actuator/info:
 * - reconciled: whether background reconciliation has succeeded
 * - driftedTopics: set of topics with detected partition drift
 * - hasPartitionDrift: boolean flag indicating active partition drift
 * - brokerAutoCreateEnabled: boolean flag indicating broker-side auto.create.topics.enable status
 */
@Component
@ConditionalOnExpression("!'${spring.kafka.bootstrap-servers:}'.trim().isEmpty()")
public class KafkaTopicInfoContributor implements InfoContributor {

    private final KafkaTopicConfigReconciler topicReconciler;

    public KafkaTopicInfoContributor(KafkaTopicConfigReconciler topicReconciler) {
        this.topicReconciler = topicReconciler;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("kafkaTopics", Map.of(
                "reconciled", topicReconciler.isReconciled(),
                "driftedTopics", topicReconciler.getDriftedTopics(),
                "hasPartitionDrift", !topicReconciler.getDriftedTopics().isEmpty(),
                "brokerAutoCreateEnabled", topicReconciler.isBrokerAutoCreateEnabled()
        ));
    }
}
