package com.notify.dlq.consumer;

import com.notify.AbstractIsolatedDatabaseTest;
import com.notify.dlq.config.DlqConsumerProperties;
import com.notify.dlq.service.AdminDlqService;
import com.notify.dlq.service.DeadLetterMaterializationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With the accepted defaults, <b>nothing consumes</b> (/ , extended by ; ).
 *
 * <p>No property is set here on purpose: this is the state an existing deployment is in after is merged. The
 * claim being proved is that merging cannot make an existing deployment start reading {@code notify.dlq.v1} or
 * any {@code .DLT} topic, and cannot create an admin client or touch a consumer group. Enabling a consumer, and
 * any offset initialization, stays a separately authorized action.
 *
 * <p>The admin read/replay services are asserted <b>present</b>, because they are not gated by the consumers: an
 * operator can inspect and resolve already-materialized entries with both consumers off. Only the ingest side is
 * conditional.
 */
class DlqDisabledByDefaultContextTest extends AbstractIsolatedDatabaseTest {

    @Autowired private ApplicationContext context;
    @Autowired private org.springframework.beans.factory.ObjectProvider<KafkaListenerEndpointRegistry> registryProvider;
    @Autowired private DlqConsumerProperties props;

    @Test
    @DisplayName("the defaults really are off, read from the bound properties")
    void defaultsAreOff() {
        assertThat(props.consumer().enabled()).isFalse();
        assertThat(props.dltConsumer().enabled()).isFalse();
        assertThat(props.anyConsumerEnabled()).isFalse();
        assertThat(props.offsetInitializationConfigured()).isFalse();
    }

    @Test
    @DisplayName("no listener container, no container factory and no consumer bean exists")
    void noIngestSideExists() {
        KafkaListenerEndpointRegistry present = registryProvider.getIfAvailable();
        if (present != null) {
            // Spring Kafka was switched on by some other feature; then no DLQ listener may be registered in it.
            assertThat(present.getListenerContainer(DlqEventConsumer.LISTENER_ID)).isNull();
            assertThat(present.getListenerContainer(DltRecordConsumer.LISTENER_ID)).isNull();
        }
        assertThat(context.getBeansOfType(DlqEventConsumer.class)).isEmpty();
        assertThat(context.getBeansOfType(DltRecordConsumer.class)).isEmpty();
        assertThat(context.getBeansOfType(DlqConsumerContainerController.class)).isEmpty();
        assertThat(context.containsBean(DlqConsumerKafkaConfig.CONTAINER_FACTORY)).isFalse();
        assertThat(context.containsBean(DltConsumerKafkaConfig.CONTAINER_FACTORY)).isFalse();
    }

    @Test
    @DisplayName("no startup gate and no offset initializer exists, so no admin client can be created")
    void noGateAndNoInitializerExists() {
        assertThat(context.getBeansOfType(DlqConsumerStartupGate.class))
                .as("the gate is the only thing that creates an Admin client; it must not exist")
                .isEmpty();
        assertThat(context.containsBean("dlqConsumerStartupGate")).isFalse();
    }

    @Test
    @DisplayName("the admin DLQ read and resolution side is unconditional, so entries stay inspectable")
    void adminSideStillExists() {
        assertThat(context.getBean(AdminDlqService.class)).isNotNull();
        assertThat(context.getBean(DeadLetterMaterializationService.class))
                .as("materialization is callable, but with no consumer nothing calls it")
                .isNotNull();
    }
}
