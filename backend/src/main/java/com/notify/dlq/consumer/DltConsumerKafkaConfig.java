package com.notify.dlq.consumer;

import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.dlq.config.DlqConsumerProperties;
import com.notify.dlq.service.DltAssociationResolver;
import com.notify.dlq.service.QuarantineService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka wiring of the dead-letter-topic consumer (/ , , decision ).
 *
 * <p>Independent of the authoritative DLQ consumer and of every delivery worker: its own flag, group, consumer
 * factory, container factory, controller and error handler, and no {@code workerPool} dependency. Enabling one
 * consumer never enables the other, and neither needs a worker.
 *
 * <p>{@code auto.offset.reset=none} and {@code autoStartup=false} for the same reasons as the DLQ consumer: a
 * position is resumed or the listener stops, and consumption begins only once the startup gate has resolved it.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(prefix = "notify.dlq.dlt-consumer", name = "enabled", havingValue = "true")
public class DltConsumerKafkaConfig {

    public static final String CONTAINER_FACTORY = "dltRecordListenerContainerFactory";

    public static Map<String, Object> consumerProperties(String bootstrapServers, DlqConsumerProperties props) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, props.dltConsumer().maxPollRecords());
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 900_000);
        config.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15_000);
        config.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "notify-dlt-consumer");
        return config;
    }

    @Bean("dltRecordConsumerFactory")
    public ConsumerFactory<String, byte[]> dltRecordConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            DlqConsumerProperties props
    ) {
        return new DefaultKafkaConsumerFactory<>(consumerProperties(bootstrapServers, props));
    }

    @Bean("dltRecordContainerController")
    public DlqConsumerContainerController dltRecordContainerController(
            KafkaListenerEndpointRegistry registry,
            DlqConsumerProperties props,
            ObjectProvider<MeterRegistry> meters
    ) {
        return new DlqConsumerContainerController(
                registry,
                DltRecordConsumer.LISTENER_ID,
                props.dltConsumer().pauseInitial(),
                props.dltConsumer().pauseMax(),
                meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean("dltRecordErrorHandler")
    public CommonErrorHandler dltRecordErrorHandler(
            DeliveryExceptionClassifier classifier,
            @Qualifier("dltRecordContainerController") DlqConsumerContainerController controller,
            DlqConsumerProperties props
    ) {
        return DlqConsumerRecoveryPolicy.errorHandler(classifier, controller, props.dltConsumer());
    }

    @Bean
    public DltRecordConsumer dltRecordConsumer(
            DltAssociationResolver resolver,
            QuarantineService quarantine,
            @Qualifier("dltRecordContainerController") DlqConsumerContainerController controller,
            ObjectProvider<MeterRegistry> meters
    ) {
        return new DltRecordConsumer(
                resolver, quarantine, controller, meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean(name = CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> dltRecordListenerContainerFactory(
            @Qualifier("dltRecordConsumerFactory") ConsumerFactory<String, byte[]> consumerFactory,
            @Qualifier("dltRecordErrorHandler") CommonErrorHandler errorHandler,
            @Qualifier("dltRecordContainerController") DlqConsumerContainerController controller,
            DlqConsumerProperties props
    ) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(props.dltConsumer().concurrency());
        factory.setCommonErrorHandler(errorHandler);
        factory.setAutoStartup(false);

        ContainerProperties container = factory.getContainerProperties();
        container.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        container.setSyncCommitTimeout(Duration.ofSeconds(10));
        container.setCommitRetries(1);
        // Same unresolved-position rule as the authoritative consumer ().
        container.setConsumerRebalanceListener(new DlqPositionGate(controller, props.dltConsumer().groupId()));
        return factory;
    }
}
