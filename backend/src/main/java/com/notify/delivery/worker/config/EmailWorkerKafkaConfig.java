package com.notify.delivery.worker.config;

import com.notify.common.config.ConditionalOnEmailWorker;
import com.notify.delivery.worker.control.EmailWorkerHealthIndicator;
import com.notify.delivery.worker.control.WorkerContainerController;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import javax.sql.DataSource;
import java.time.Clock;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka runtime wiring of the email worker (plan section 9). The project depends on plain {@code spring-kafka}
 * (Boot's Kafka auto-configuration is not present), so everything here is explicit. When the worker gate is off no
 * listener, container, consumer, DLT producer, or controller exists, so unrelated tests and runs are unaffected ().
 */
@Configuration
@EnableKafka
@ConditionalOnEmailWorker
public class EmailWorkerKafkaConfig {

    public static final String CONTAINER_FACTORY = "emailKafkaListenerContainerFactory";

    /** Consumer properties exactly as approved in ; exposed for the configuration test. */
    public static Map<String, Object> consumerProperties(String bootstrapServers) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // earliest is required: latest would silently skip events that already exist (never skip silently)
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 900_000);
        config.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15_000);
        config.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "notify-email-worker");
        return config;
    }

    @Bean
    public ConsumerFactory<String, byte[]> emailConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers
    ) {
        return new DefaultKafkaConsumerFactory<>(consumerProperties(bootstrapServers));
    }

    /**
     * Since the controller is per channel, so it is declared here under the channel's bean name instead of being
     * component-scanned: the webhook worker gets its own instance bound to its own listener id.
     */
    @Bean("emailWorkerContainerController")
    public WorkerContainerController emailWorkerContainerController(
            KafkaListenerEndpointRegistry registry,
            EmailWorkerProperties props,
            @Qualifier("workerPool") DataSource workerPool,
            ObjectProvider<MeterRegistry> meters,
            Clock clock
    ) {
        return new WorkerContainerController(
                registry, WorkerContainerController.LISTENER_ID, props, workerPool, meters, clock);
    }

    /** Bean name decides the health component name: {@code emailWorker} (), unchanged from . */
    @Bean("emailWorkerHealthIndicator")
    public EmailWorkerHealthIndicator emailWorkerHealthIndicator(
            @Qualifier("emailWorkerContainerController") WorkerContainerController controller,
            EmailWorkerProperties props,
            Clock clock
    ) {
        return new EmailWorkerHealthIndicator(controller, props, clock);
    }

    @Bean(name = CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> emailKafkaListenerContainerFactory(
            @Qualifier("emailConsumerFactory") ConsumerFactory<String, byte[]> consumerFactory,
            @Qualifier("emailWorkerErrorHandler") CommonErrorHandler errorHandler,
            @Qualifier("emailWorkerContainerController") WorkerContainerController controller,
            EmailWorkerProperties props,
            @Value("${notify.worker.concurrency.email:3}") int concurrency
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
