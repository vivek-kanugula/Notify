package com.notify.delivery.worker.config;

import com.notify.common.config.ConditionalOnInAppWorker;
import com.notify.delivery.worker.control.InAppWorkerHealthIndicator;
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
 * Kafka runtime wiring of the in-app worker (/ ). It mirrors the email and webhook workers: the same 
 * consumer settings, the same manual acknowledgment mode, the same failure policy - but its own topic, consumer
 * group, listener container, dead-letter template, controller and health indicator, so one channel can never pause,
 * stop or starve another.
 *
 * <p>When the in-app gate is off, none of these beans exist (extended), so unrelated tests and runs are
 * unaffected.
 */
@Configuration
@EnableKafka
@ConditionalOnInAppWorker
public class InAppWorkerKafkaConfig {

    public static final String CONTAINER_FACTORY = "inappKafkaListenerContainerFactory";

    /** Same approved consumer properties as the other channel workers (), with the in-app client id. */
    public static Map<String, Object> consumerProperties(String bootstrapServers) {
        Map<String, Object> config = new HashMap<>(EmailWorkerKafkaConfig.consumerProperties(bootstrapServers));
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.CLIENT_ID_CONFIG, "notify-inapp-worker");
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return config;
    }

    @Bean
    public ConsumerFactory<String, byte[]> inappConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers
    ) {
        return new DefaultKafkaConsumerFactory<>(consumerProperties(bootstrapServers));
    }

    @Bean("inappWorkerContainerController")
    public WorkerContainerController inappWorkerContainerController(
            KafkaListenerEndpointRegistry registry,
            InAppWorkerProperties props,
            @Qualifier("workerPool") DataSource workerPool,
            ObjectProvider<MeterRegistry> meters,
            Clock clock
    ) {
        return new WorkerContainerController(
                registry, WorkerContainerController.INAPP_LISTENER_ID, props, workerPool, meters, clock);
    }

    /** Bean name decides the health component name: {@code inappWorker}. Outside the readiness group (). */
    @Bean("inappWorkerHealthIndicator")
    public InAppWorkerHealthIndicator inappWorkerHealthIndicator(
            @Qualifier("inappWorkerContainerController") WorkerContainerController controller,
            InAppWorkerProperties props,
            Clock clock
    ) {
        return new InAppWorkerHealthIndicator(controller, props, clock);
    }

    @Bean(name = CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> inappKafkaListenerContainerFactory(
            @Qualifier("inappConsumerFactory") ConsumerFactory<String, byte[]> consumerFactory,
            @Qualifier("inappWorkerErrorHandler") CommonErrorHandler errorHandler,
            @Qualifier("inappWorkerContainerController") WorkerContainerController controller,
            InAppWorkerProperties props,
            @Value("${notify.worker.concurrency.inapp:3}") int concurrency
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