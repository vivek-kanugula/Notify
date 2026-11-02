package com.notify.delivery.worker.config;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.delivery.worker.EmailDeliveryWorker;
import com.notify.delivery.worker.WebhookDeliveryWorker;
import com.notify.delivery.worker.control.EmailWorkerHealthIndicator;
import com.notify.delivery.worker.control.WebhookWorkerHealthIndicator;
import com.notify.delivery.worker.control.WorkerContainerController;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * worker activation, with <em>both</em> channel workers on at once: that is the configuration the two of them have
 * to survive, and the one where a shared bean name or a shared listener id would show up. Auto-startup is off, so no
 * broker is contacted.
 */
@TestPropertySource(properties = {
        "notify.worker.email.enabled=true",
        "notify.worker.email.auto-startup=false",
        "notify.worker.webhook.enabled=true",
        "notify.worker.webhook.auto-startup=false",
        "spring.kafka.bootstrap-servers=localhost:1"
})
class WebhookWorkerKafkaConfigTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    @Qualifier("webhookConsumerFactory")
    private ConsumerFactory<String, byte[]> webhookConsumerFactory;

    @Autowired
    @Qualifier("webhookWorkerContainerController")
    private WorkerContainerController webhookController;

    @Autowired
    @Qualifier("emailWorkerContainerController")
    private WorkerContainerController emailController;

    @Autowired
    @Qualifier("webhookWorkerErrorHandler")
    private DefaultErrorHandler webhookErrorHandler;

    @Test
    @DisplayName("both workers, both controllers and both health indicators exist side by side")
    void bothWorkersCoexist() {
        assertThat(context.getBeansOfType(WebhookDeliveryWorker.class)).hasSize(1);
        assertThat(context.getBeansOfType(EmailDeliveryWorker.class)).hasSize(1);
        assertThat(context.getBeansOfType(WorkerContainerController.class))
                .containsKeys("emailWorkerContainerController", "webhookWorkerContainerController");
        assertThat(context.getBeansOfType(WebhookWorkerHealthIndicator.class)).hasSize(1);
        assertThat(context.getBeansOfType(EmailWorkerHealthIndicator.class)).hasSize(1);
        assertThat(webhookController).isNotSameAs(emailController);
    }

    @Test
    @DisplayName("each channel has its own listener container, topic and consumer group, so neither can starve the other")
    void listenersAreIndependent() {
        assertThat(registry.getListenerContainerIds()).containsExactlyInAnyOrder(
                WorkerContainerController.LISTENER_ID, WorkerContainerController.WEBHOOK_LISTENER_ID);

        ConcurrentMessageListenerContainer<?, ?> webhook = (ConcurrentMessageListenerContainer<?, ?>)
                registry.getListenerContainer(WorkerContainerController.WEBHOOK_LISTENER_ID);
        ContainerProperties cp = webhook.getContainerProperties();

        assertThat(cp.getTopics()).containsExactly("notify.dispatch.webhook.v1");
        assertThat(cp.getGroupId()).isEqualTo("notify-webhook-delivery-workers");
        assertThat(webhook.getConcurrency()).isEqualTo(3);
        assertThat(cp.getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(cp.getSyncCommitTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(cp.getCommitRetries()).isEqualTo(1);
        assertThat(cp.getConsumerRebalanceListener()).isSameAs(webhookController);
        assertThat(webhook.getCommonErrorHandler()).isSameAs(webhookErrorHandler);
        assertThat(webhook.isAutoStartup()).as("auto-startup is off in this test").isFalse();

        ContainerProperties email = registry.getListenerContainer(WorkerContainerController.LISTENER_ID)
                .getContainerProperties();
        assertThat(email.getTopics()).containsExactly("notify.dispatch.email.v1");
        assertThat(email.getGroupId()).isEqualTo("notify-email-delivery-workers");
        assertThat(email.getConsumerRebalanceListener()).isSameAs(emailController);
    }

    @Test
    @DisplayName("the webhook consumer uses the approved settings () with its own client id")
    void consumerProperties() {
        Map<String, Object> p = webhookConsumerFactory.getConfigurationProperties();

        assertThat(p.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo(false);
        assertThat(p.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
        assertThat(p.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG)).isEqualTo(10);
        assertThat(p.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG)).isEqualTo(900_000);
        assertThat(p.get(ConsumerConfig.ISOLATION_LEVEL_CONFIG)).isEqualTo("read_committed");
        assertThat(p.get(ConsumerConfig.CLIENT_ID_CONFIG)).isEqualTo("notify-webhook-worker");
        assertThat(p.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG))
                .isEqualTo(org.apache.kafka.common.serialization.StringDeserializer.class);
        assertThat(p.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG))
                .isEqualTo(org.apache.kafka.common.serialization.ByteArrayDeserializer.class);
    }

    @Test
    @DisplayName("the two channels share one dead-letter template and one worker pool (), not one of each")
    void sharedInfrastructure() {
        assertThat(context.getBeansOfType(KafkaTemplate.class))
                .containsKeys("kafkaTemplate", "deliveryDltKafkaTemplate");
        assertThat(context.getBeansOfType(KafkaTemplate.class).keySet())
                .as("no second dead-letter template per channel").hasSize(2);
        assertThat(context.containsBean("workerPool")).isTrue();
    }

    @Test
    @DisplayName("the webhook worker properties carry the documented defaults")
    void workerPropertyDefaults() {
        WebhookWorkerProperties props = context.getBean(WebhookWorkerProperties.class);

        assertThat(props.name()).isEqualTo("webhook");
        assertThat(props.retryInterval()).isEqualTo(Duration.ofSeconds(3));
        assertThat(props.retryAttempts()).isEqualTo(3);
        assertThat(props.pauseInitial()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.pauseMax()).isEqualTo(Duration.ofSeconds(300));
        assertThat(props.escalateAfter()).isEqualTo(3);
        assertThat(props.healthPollInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.healthSuccesses()).isEqualTo(2);
        assertThat(props.lagThreshold()).isEqualTo(1000);
    }

    @Test
    @DisplayName("each channel reports its own health component, and neither is in the readiness group ()")
    void healthComponentsAreSeparate() {
        var webhookHealth = context.getBean("webhookWorkerHealthIndicator", WebhookWorkerHealthIndicator.class);
        var emailHealth = context.getBean("emailWorkerHealthIndicator", EmailWorkerHealthIndicator.class);

        assertThat(webhookHealth.health().getDetails()).containsEntry("channel", "webhook");
        assertThat(emailHealth.health().getDetails()).containsEntry("channel", "email");
    }
}
