package com.notify.kafka;

import com.notify.kafka.config.KafkaProducerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaProducerConfigTest {

    @Test
    @DisplayName("KafkaProducerConfig sets max.block.ms, acks=all, enable.idempotence, and timeouts correctly")
    void producerFactory_setsBoundedMaxBlockMsAndInvariants() {
        KafkaProducerConfig config = new KafkaProducerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "perMessageTimeoutMs", 1500L);

        ProducerFactory<String, String> factory = config.producerFactory();
        assertThat(factory).isInstanceOf(DefaultKafkaProducerFactory.class);

        Map<String, Object> props = ((DefaultKafkaProducerFactory<String, String>) factory).getConfigurationProperties();
        assertThat(props.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)).isEqualTo("localhost:9092");
        assertThat(props.get(ProducerConfig.ACKS_CONFIG)).isEqualTo("all");
        assertThat(props.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isEqualTo(true);
        assertThat(props.get(ProducerConfig.LINGER_MS_CONFIG)).isEqualTo(5);
        assertThat(props.get(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(1500L);
        assertThat(props.get(ProducerConfig.RETRIES_CONFIG)).isEqualTo(3);
    }
}
