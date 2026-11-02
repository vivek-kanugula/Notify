package com.notify.dlq.consumer;

import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.dlq.config.DlqConsumerProperties;
import com.notify.dlq.service.DeadLetterMaterializationService;
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

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka wiring of the authoritative dead-letter consumer (/ , , decision ).
 *
 * <p><b>Consumer-owned, worker-free.</b> It declares its own consumer factory, container factory, container
 * controller and error handler, and requires <b>no</b> {@code workerPool} DataSource: that bean is
 * {@code @Conditional(OnAnyWorkerCondition.class)} and does not exist when every delivery worker is disabled,
 * which is the default and the state this consumer must work in. The only things reused are worker-free: the pure
 * {@link DeliveryExceptionClassifier} and the shared recovery semantics.
 *
 * <p><b>{@code auto.offset.reset=none}, fixed.</b> A listener resumes valid committed offsets and nothing else.
 * A missing or out-of-range position makes the consumer fail its assignment, which
 * {@link DlqConsumerStartupGate} turns into a stopped listener with actionable evidence - never a silent skip and
 * never a silent replay ().
 *
 * <p><b>Listener startup is blocked.</b> {@code autoStartup} is false on the factory and on the listener, so the
 * container exists but does not consume until {@link DlqConsumerStartupGate} has resolved the offset position.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(prefix = "notify.dlq.consumer", name = "enabled", havingValue = "true")
public class DlqConsumerKafkaConfig {

    public static final String CONTAINER_FACTORY = "dlqEventListenerContainerFactory";

    /** Exposed for the configuration test, which asserts the fixed reset policy rather than trusting a comment. */
    public static Map<String, Object> consumerProperties(String bootstrapServers, DlqConsumerProperties props) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // none, deliberately and not configurable: see the class javadoc and .
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, props.consumer().maxPollRecords());
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 900_000);
        config.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15_000);
        config.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "notify-dlq-consumer");
        return config;
    }

    @Bean("dlqEventConsumerFactory")
    public ConsumerFactory<String, byte[]> dlqEventConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            DlqConsumerProperties props
    ) {
        return new DefaultKafkaConsumerFactory<>(consumerProperties(bootstrapServers, props));
    }

    @Bean("dlqEventContainerController")
    public DlqConsumerContainerController dlqEventContainerController(
            KafkaListenerEndpointRegistry registry,
            DlqConsumerProperties props,
            ObjectProvider<MeterRegistry> meters
    ) {
        return new DlqConsumerContainerController(
                registry,
                DlqEventConsumer.LISTENER_ID,
                props.consumer().pauseInitial(),
                props.consumer().pauseMax(),
                meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean("dlqEventErrorHandler")
    public CommonErrorHandler dlqEventErrorHandler(
            DeliveryExceptionClassifier classifier,
            @Qualifier("dlqEventContainerController") DlqConsumerContainerController controller,
            DlqConsumerProperties props
    ) {
        return DlqConsumerRecoveryPolicy.errorHandler(classifier, controller, props.consumer());
    }

    @Bean
    public DlqEventConsumer dlqEventConsumer(
            DeadLetterMaterializationService materialization,
            QuarantineService quarantine,
            @Qualifier("dlqEventContainerController") DlqConsumerContainerController controller,
            ObjectProvider<MeterRegistry> meters
    ) {
        return new DlqEventConsumer(
                materialization, quarantine, controller, meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean(name = CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> dlqEventListenerContainerFactory(
            @Qualifier("dlqEventConsumerFactory") ConsumerFactory<String, byte[]> consumerFactory,
            @Qualifier("dlqEventErrorHandler") CommonErrorHandler errorHandler,
            @Qualifier("dlqEventContainerController") DlqConsumerContainerController controller,
            DlqConsumerProperties props
    ) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(props.consumer().concurrency());
        factory.setCommonErrorHandler(errorHandler);
        // Never auto-start: the startup gate decides, after the offset position is resolved.
        factory.setAutoStartup(false);

        ContainerProperties container = factory.getContainerProperties();
        container.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        container.setSyncCommitTimeout(java.time.Duration.ofSeconds(10));
        container.setCommitRetries(1);
        // Stops the listener when an assigned partition has no committed position, with the topic, the partition
        // and the group in one recorded reason ().
        container.setConsumerRebalanceListener(new DlqPositionGate(controller, props.consumer().groupId()));
        return factory;
    }
}
