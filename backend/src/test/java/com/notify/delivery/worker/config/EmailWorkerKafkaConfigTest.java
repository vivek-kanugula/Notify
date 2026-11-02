package com.notify.delivery.worker.config;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.delivery.worker.EmailDeliveryWorker;
import com.notify.delivery.worker.control.EmailWorkerHealthIndicator;
import com.notify.delivery.worker.control.WorkerContainerController;
import com.notify.kafka.producer.KafkaDispatchProducer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K1 (present with the property) and K2 (exact consumer / producer / container properties). Same property set as
 * PoolRoutingIntegrationTest so the Spring context is shared. Auto-startup is off: no broker is contacted.
 */
@TestPropertySource(properties = {
        "notify.worker.email.enabled=true",
        "notify.worker.email.auto-startup=false",
        // A syntactically valid but unreachable broker: Spring's test-context cache pauses and restarts idle contexts, and a
        // restart must never fail merely because the (never-started) listener container is asked to build a consumer.
        "spring.kafka.bootstrap-servers=localhost:1"
})
class EmailWorkerKafkaConfigTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    @Qualifier("emailConsumerFactory")
    private ConsumerFactory<String, byte[]> consumerFactory;

    @Autowired
    @Qualifier("deliveryDltKafkaTemplate")
    private KafkaTemplate<String, byte[]> dltTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private WorkerContainerController controller;

    @Autowired
    @Qualifier("emailWorkerErrorHandler")
    private DefaultErrorHandler errorHandler;

    @Test
    @DisplayName("K1 with notify.worker.email.enabled=true the worker, controller, health indicator, pool, factory and DLT template exist")
    void beansPresentWhenEnabled() {
        assertThat(context.getBeansOfType(EmailDeliveryWorker.class)).hasSize(1);
        assertThat(context.getBeansOfType(WorkerContainerController.class)).hasSize(1);
        assertThat(context.getBeansOfType(EmailWorkerHealthIndicator.class)).hasSize(1);
        assertThat(context.containsBean("workerPool")).isTrue();
        assertThat(context.containsBean(EmailWorkerKafkaConfig.CONTAINER_FACTORY)).isTrue();
        assertThat(context.containsBean("deliveryDltKafkaTemplate")).isTrue();
        assertThat(registry.getListenerContainerIds()).containsExactly(WorkerContainerController.LISTENER_ID);
        assertThat(registry.getListenerContainer(WorkerContainerController.LISTENER_ID).isAutoStartup())
                .as("auto-startup is off in this test").isFalse();
    }

    @Test
    @DisplayName("K1 the outbox relay's String KafkaTemplate and producer are untouched and still resolvable alongside the DLT template")
    void relayTemplateUntouched() {
        assertThat(context.getBeansOfType(KafkaTemplate.class)).containsKeys("kafkaTemplate", "deliveryDltKafkaTemplate");
        assertThat(context.getBean(KafkaDispatchProducer.class)).isNotNull();
        Map<String, Object> relayProducer = context.getBean("producerFactory", ProducerFactory.class).getConfigurationProperties();
        assertThat(relayProducer.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG))
                .isEqualTo(org.apache.kafka.common.serialization.StringSerializer.class);
    }

    @Test
    @DisplayName("K2 consumer properties are exactly the approved set ()")
    void consumerProperties() {
        Map<String, Object> p = consumerFactory.getConfigurationProperties();
        assertThat(p.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo(false);
        assertThat(p.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
        assertThat(p.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG)).isEqualTo(10);
        assertThat(p.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG)).isEqualTo(900_000);
        assertThat(p.get(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG)).isEqualTo(15_000);
        assertThat(p.get(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG)).isEqualTo(10_000);
        assertThat(p.get(ConsumerConfig.ISOLATION_LEVEL_CONFIG)).isEqualTo("read_committed");
        assertThat(p.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)).isEqualTo(org.apache.kafka.common.serialization.StringDeserializer.class);
        assertThat(p.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)).isEqualTo(org.apache.kafka.common.serialization.ByteArrayDeserializer.class);
        assertThat(EmailWorkerKafkaConfig.consumerProperties("x:1")).containsKeys(p.keySet().toArray(new String[0]));
    }

    @Test
    @DisplayName("K2 dead-letter producer properties are exactly the approved set and distinct from the relay producer")
    void dltProducerProperties() {
        Map<String, Object> p = dltTemplate.getProducerFactory().getConfigurationProperties();
        assertThat(p.get(ProducerConfig.ACKS_CONFIG)).isEqualTo("all");
        assertThat(p.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isEqualTo(true);
        assertThat(p.get(ProducerConfig.LINGER_MS_CONFIG)).isEqualTo(5);
        assertThat(p.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG)).isEqualTo(4_000);
        assertThat(p.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG)).isEqualTo(6_000);
        assertThat(p.get(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(3_000);
        assertThat(p.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)).isEqualTo(org.apache.kafka.common.serialization.ByteArraySerializer.class);
        assertThat(p.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)).isEqualTo(org.apache.kafka.common.serialization.StringSerializer.class);
    }

    @Test
    @DisplayName("K2 the listener container is record-mode, manual-immediate, 3 consumers, group and topic as configured, with our handler and rebalance listener")
    void containerProperties() {
        ConcurrentMessageListenerContainer<?, ?> container =
                (ConcurrentMessageListenerContainer<?, ?>) registry.getListenerContainer(WorkerContainerController.LISTENER_ID);
        ContainerProperties cp = container.getContainerProperties();

        assertThat(container.getConcurrency()).isEqualTo(3);
        assertThat(cp.getGroupId()).isEqualTo("notify-email-delivery-workers");
        assertThat(cp.getTopics()).containsExactly("notify.dispatch.email.v1");
        assertThat(cp.getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(cp.getSyncCommitTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(cp.getCommitRetries()).isEqualTo(1);
        assertThat(cp.getConsumerRebalanceListener()).isSameAs(controller);
        assertThat(container.getCommonErrorHandler()).isSameAs(errorHandler);
        assertThat(cp.getMessageListener()).isNotNull();
    }

    @Test
    @DisplayName("K2 the worker properties carry the documented defaults")
    void workerPropertyDefaults() {
        EmailWorkerProperties props = context.getBean(EmailWorkerProperties.class);
        assertThat(props.retryInterval()).isEqualTo(Duration.ofSeconds(3));
        assertThat(props.retryAttempts()).isEqualTo(3);
        assertThat(props.pauseInitial()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.pauseMax()).isEqualTo(Duration.ofSeconds(300));
        assertThat(props.escalateAfter()).isEqualTo(3);
        assertThat(props.healthPollInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.healthSuccesses()).isEqualTo(2);
        assertThat(props.lagThreshold()).isEqualTo(1000);
    }
}
