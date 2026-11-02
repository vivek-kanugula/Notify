package com.notify.kafka;

import com.notify.kafka.config.KafkaTopicsConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTopicsConfigTest {

    private final KafkaTopicsConfig config = new KafkaTopicsConfig();

    @Test
    @DisplayName("KafkaTopicsConfig declares all 8 canonical topics with correct partition and retention contracts")
    void declaresAllTopicsWithCorrectContracts() {
        Map<String, NewTopic> topics = Map.of(
                KafkaTopicsConfig.TOPIC_DISPATCH_EMAIL, config.dispatchEmailTopic(),
                KafkaTopicsConfig.TOPIC_DISPATCH_WEBHOOK, config.dispatchWebhookTopic(),
                KafkaTopicsConfig.TOPIC_DISPATCH_INAPP, config.dispatchInappTopic(),
                KafkaTopicsConfig.TOPIC_DISPATCH_EMAIL_DLT, config.dispatchEmailDltTopic(),
                KafkaTopicsConfig.TOPIC_DISPATCH_WEBHOOK_DLT, config.dispatchWebhookDltTopic(),
                KafkaTopicsConfig.TOPIC_DISPATCH_INAPP_DLT, config.dispatchInappDltTopic(),
                KafkaTopicsConfig.TOPIC_DLQ, config.dlqTopic(),
                KafkaTopicsConfig.TOPIC_STATUS_EVENTS, config.statusEventsTopic()
        );

        assertThat(topics).hasSize(8);

        // Dispatch topics
        assertThat(topics.get(KafkaTopicsConfig.TOPIC_DISPATCH_EMAIL).numPartitions()).isEqualTo(6);
        assertThat(topics.get(KafkaTopicsConfig.TOPIC_DISPATCH_WEBHOOK).numPartitions()).isEqualTo(6);
        assertThat(topics.get(KafkaTopicsConfig.TOPIC_DISPATCH_INAPP).numPartitions()).isEqualTo(3);

        // DLT topics
        assertThat(topics.get(KafkaTopicsConfig.TOPIC_DISPATCH_EMAIL_DLT).numPartitions()).isEqualTo(3);
        assertThat(topics.get(KafkaTopicsConfig.TOPIC_DISPATCH_WEBHOOK_DLT).numPartitions()).isEqualTo(3);
        assertThat(topics.get(KafkaTopicsConfig.TOPIC_DISPATCH_INAPP_DLT).numPartitions()).isEqualTo(3);

        // DLQ and Status-Events
        assertThat(topics.get(KafkaTopicsConfig.TOPIC_DLQ).numPartitions()).isEqualTo(3);
        assertThat(topics.get(KafkaTopicsConfig.TOPIC_STATUS_EVENTS).numPartitions()).isEqualTo(3);

        // Check 3-day retention on dispatch
        String retention3Days = String.valueOf(3L * 24 * 60 * 60 * 1000);
        assertThat(topics.get(KafkaTopicsConfig.TOPIC_DISPATCH_EMAIL).configs().get("retention.ms")).isEqualTo(retention3Days);

        // Check 14-day retention on DLT and DLQ
        String retention14Days = String.valueOf(14L * 24 * 60 * 60 * 1000);
        assertThat(topics.get(KafkaTopicsConfig.TOPIC_DISPATCH_EMAIL_DLT).configs().get("retention.ms")).isEqualTo(retention14Days);
        assertThat(topics.get(KafkaTopicsConfig.TOPIC_DLQ).configs().get("retention.ms")).isEqualTo(retention14Days);
    }

    @Test
    @DisplayName("resolveDispatchTopic exhaustively maps each Channel enum to its contracted topic name without underscore")
    void resolveDispatchTopic_mapsExhaustivelyAndRejectsNull() {
        assertThat(KafkaTopicsConfig.resolveDispatchTopic(com.notify.common.model.Channel.EMAIL))
                .isEqualTo("notify.dispatch.email.v1");

        assertThat(KafkaTopicsConfig.resolveDispatchTopic(com.notify.common.model.Channel.WEBHOOK))
                .isEqualTo("notify.dispatch.webhook.v1");

        assertThat(KafkaTopicsConfig.resolveDispatchTopic(com.notify.common.model.Channel.IN_APP))
                .isEqualTo("notify.dispatch.inapp.v1")
                .doesNotContain("in_app");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> KafkaTopicsConfig.resolveDispatchTopic(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
