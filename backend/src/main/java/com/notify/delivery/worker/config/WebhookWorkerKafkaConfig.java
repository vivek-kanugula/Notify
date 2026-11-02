package com.notify.delivery.worker.config;

import com.notify.common.config.ConditionalOnWebhookWorker;
import com.notify.delivery.worker.control.WebhookWorkerHealthIndicator;
import com.notify.delivery.worker.control.WorkerContainerController;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka runtime wiring of the webhook worker (). It mirrors the email worker: the same consumer settings, the
 * same manual acknowledgment mode, the same failure policy — but its own topic, consumer group, listener container,
 * dead-letter template, controller and health indicator, so one channel can never pause, stop or starve the other.
 *
 * <p>When the webhook gate is off, none of these beans exist (extended), so unrelated tests and runs are
 * unaffected.
 */
@Configuration
@EnableKafka
@ConditionalOnWebhookWorker
public class WebhookWorkerKafkaConfig {

    public static final String CONTAINER_FACTORY = "webhookKafkaListenerContainerFactory";

    /** Same approved consumer properties as the email worker (), with the webhook client id. */
    public static Map<String, Object> consumerProperties(String bootstrapServers) {
        Map<String, Object> config = new HashMap<>(EmailWorkerKafkaConfig.consumerProperties(bootstrapServers));
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.CLIENT_ID_CONFIG, "notify-webhook-worker");
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return config;
    }

    @Bean
    public ConsumerFactory<String, byte[]> webhookConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers
    ) {
        return new DefaultKafkaConsumerFactory<>(consumerProperties(bootstrapServers));
    }

    @Bean("webhookWorkerContainerController")
    public WorkerContainerController webhookWorkerContainerController(
            KafkaListenerEndpointRegistry registry,
            WebhookWorkerProperties props,
            @Qualifier("workerPool") DataSource workerPool,
            ObjectProvider<MeterRegistry> meters,
            Clock clock
    ) {
        return new WorkerContainerController(
                registry, WorkerContainerController.WEBHOOK_LISTENER_ID, props, workerPool, meters, clock);
    }

    /** Bean name decides the health component name: {@code webhookWorker}. Outside the readiness group (). */
    @Bean("webhookWorkerHealthIndicator")
    public WebhookWorkerHealthIndicator webhookWorkerHealthIndicator(
            @Qualifier("webhookWorkerContainerController") WorkerContainerController controller,
            WebhookWorkerProperties props,
            Clock clock
    ) {
        return new WebhookWorkerHealthIndicator(controller, props, clock);
    }

    @Bean(name = CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> webhookKafkaListenerContainerFactory(
            @Qualifier("webhookConsumerFactory") ConsumerFactory<String, byte[]> consumerFactory,
            @Qualifier("webhookWorkerErrorHandler") CommonErrorHandler errorHandler,
            @Qualifier("webhookWorkerContainerController") WorkerContainerController controller,
            WebhookWorkerProperties props,
            @Value("${notify.worker.concurrency.webhook:3}") int concurrency
    ) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(concurrency);
        factory.setCommonErrorHandler(errorHandler);
        factory.setAutoStartup(props.autoStartup());

        ContainerProperties container = factory.getContainerProperties();
        container.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        container.setSyncCommitTimeout(Duration.ofSeconds(10));
        container.setCommitRetries(1);
        container.setConsumerRebalanceListener(controller);
        return factory;
    }
}
