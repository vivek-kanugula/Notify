package com.notify.delivery.worker.config;

import com.notify.common.config.OnAnyWorkerCondition;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * The dead-letter producer shared by every channel delivery worker (; the beans were introduced in inside the
 * email configuration). It carries no channel-specific setting and only ever publishes a byte-identical copy of an
 * original record to that record's own {@code .DLT} topic, so one producer serves all channels.
 *
 * <p>Exists when at least one worker is enabled; the String template used by the outbox relay is untouched.
 */
@Configuration
@Conditional(OnAnyWorkerCondition.class)
public class WorkerDltConfig {

    /** Dead-letter producer properties; exposed for the configuration tests. */
    public static Map<String, Object> dltProducerProperties(String bootstrapServers) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 4_000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 6_000);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3_000);
        return config;
    }

    @Bean
    public ProducerFactory<String, byte[]> deliveryDltProducerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers
    ) {
        return new DefaultKafkaProducerFactory<>(dltProducerProperties(bootstrapServers));
    }

    /** Distinct template for byte-identical dead-lettering; the relay's String template is unchanged. */
    @Bean(name = "deliveryDltKafkaTemplate")
    public KafkaTemplate<String, byte[]> deliveryDltKafkaTemplate(
            @Qualifier("deliveryDltProducerFactory") ProducerFactory<String, byte[]> producerFactory
    ) {
        return new KafkaTemplate<>(producerFactory);
    }
}
