package com.notify.kafka.config;

import com.notify.common.model.Channel;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.HashMap;
import java.util.Map;

/**
 * Programmatic Kafka topic provisioning for Notify per CONTRACTS.md.
 * Topics are created on application startup with explicit partition and retention configurations.
 */
@Configuration
@ConditionalOnExpression("!'${spring.kafka.bootstrap-servers:}'.trim().isEmpty()")
public class KafkaTopicsConfig {

    public static final String TOPIC_DISPATCH_EMAIL = "notify.dispatch.email.v1";
    public static final String TOPIC_DISPATCH_WEBHOOK = "notify.dispatch.webhook.v1";
    public static final String TOPIC_DISPATCH_INAPP = "notify.dispatch.inapp.v1";

    public static final String TOPIC_DISPATCH_EMAIL_DLT = "notify.dispatch.email.v1.DLT";
    public static final String TOPIC_DISPATCH_WEBHOOK_DLT = "notify.dispatch.webhook.v1.DLT";
    public static final String TOPIC_DISPATCH_INAPP_DLT = "notify.dispatch.inapp.v1.DLT";

    public static final String TOPIC_DLQ = "notify.dlq.v1";
    public static final String TOPIC_STATUS_EVENTS = "notify.status-events.v1";

    private static final String RETENTION_3_DAYS_MS = String.valueOf(3L * 24 * 60 * 60 * 1000);
    private static final String RETENTION_14_DAYS_MS = String.valueOf(14L * 24 * 60 * 60 * 1000);

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.admin.auto-create:false}")
    private boolean autoCreate;

    /**
     * Exhaustively maps Channel to contracted dispatch Kafka topic name.
     */
    public static String resolveDispatchTopic(Channel channel) {
        if (channel == null) {
            throw new IllegalArgumentException("Channel cannot be null for dispatch topic resolution");
        }
        return switch (channel) {
            case EMAIL -> TOPIC_DISPATCH_EMAIL;
            case WEBHOOK -> TOPIC_DISPATCH_WEBHOOK;
            case IN_APP -> TOPIC_DISPATCH_INAPP;
        };
    }

    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000);
        configs.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        configs.put(AdminClientConfig.RETRIES_CONFIG, 1);
        KafkaAdmin admin = new KafkaAdmin(configs);
        admin.setAutoCreate(autoCreate);
        return admin;
    }

    @Bean
    public NewTopic dispatchEmailTopic() {
        return TopicBuilder.name(TOPIC_DISPATCH_EMAIL)
                .partitions(6)
                .replicas(1)
                .configs(Map.of("retention.ms", RETENTION_3_DAYS_MS))
                .build();
    }

    @Bean
    public NewTopic dispatchWebhookTopic() {
        return TopicBuilder.name(TOPIC_DISPATCH_WEBHOOK)
                .partitions(6)
                .replicas(1)
                .configs(Map.of("retention.ms", RETENTION_3_DAYS_MS))
                .build();
    }

    @Bean
    public NewTopic dispatchInappTopic() {
        return TopicBuilder.name(TOPIC_DISPATCH_INAPP)
                .partitions(3)
                .replicas(1)
                .configs(Map.of("retention.ms", RETENTION_3_DAYS_MS))
                .build();
    }

    @Bean
    public NewTopic dispatchEmailDltTopic() {
        return TopicBuilder.name(TOPIC_DISPATCH_EMAIL_DLT)
                .partitions(3)
                .replicas(1)
                .configs(Map.of("retention.ms", RETENTION_14_DAYS_MS))
                .build();
    }

    @Bean
    public NewTopic dispatchWebhookDltTopic() {
        return TopicBuilder.name(TOPIC_DISPATCH_WEBHOOK_DLT)
                .partitions(3)
                .replicas(1)
                .configs(Map.of("retention.ms", RETENTION_14_DAYS_MS))
                .build();
    }

    @Bean
    public NewTopic dispatchInappDltTopic() {
        return TopicBuilder.name(TOPIC_DISPATCH_INAPP_DLT)
                .partitions(3)
                .replicas(1)
                .configs(Map.of("retention.ms", RETENTION_14_DAYS_MS))
                .build();
    }

    @Bean
    public NewTopic dlqTopic() {
        return TopicBuilder.name(TOPIC_DLQ)
                .partitions(3)
                .replicas(1)
                .configs(Map.of("retention.ms", RETENTION_14_DAYS_MS))
                .build();
    }

    @Bean
    public NewTopic statusEventsTopic() {
        return TopicBuilder.name(TOPIC_STATUS_EVENTS)
                .partitions(3)
                .replicas(1)
                .configs(Map.of("retention.ms", RETENTION_3_DAYS_MS))
                .build();
    }
}
