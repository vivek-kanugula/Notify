package com.notify.dlq.consumer;

import com.notify.AbstractIsolatedDatabaseTest;
import com.notify.delivery.worker.control.WorkerContainerController;
import com.notify.dlq.service.DeadLetterMaterializationService;
import com.notify.dlq.service.QuarantineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both consumers exist and are independently testable with **every delivery worker disabled** (/ , ,
 * decision ; ).
 *
 * <p>This is the configuration that caught a real design error during planning: {@code WorkerContainerController}
 * takes the {@code workerPool} DataSource, and that bean is {@code @Conditional(OnAnyWorkerCondition.class)} - it
 * does not exist here. A consumer that reused it would fail to start, or would drag worker infrastructure into a
 * workers-off deployment. So the assertions are deliberately negative as well as positive: the consumer beans are
 * present, and **no** worker bean of any kind is.
 *
 * <p>The listeners are registered but **not started**: {@code autoStartup} is false, and the startup gate starts
 * them only once the offset position is resolved. Here no initialization input is configured, so the gate starts
 * them and they resume committed offsets - the ordinary path.
 */
@TestPropertySource(properties = {
        "notify.dlq.consumer.enabled=true",
        "notify.dlq.dlt-consumer.enabled=true",
        "notify.worker.email.enabled=false",
        "notify.worker.webhook.enabled=false",
        "notify.worker.inapp.enabled=false",
        "notify.recovery.enabled=false",
        "notify.reconciler.enabled=false",
        "notify.outbox.relay-enabled=false"
})
class DlqConsumerWithoutWorkersContextTest extends AbstractIsolatedDatabaseTest {

    @Autowired private ApplicationContext context;
    @Autowired private KafkaListenerEndpointRegistry registry;

    @Test
    @DisplayName("both consumers are wired, and no worker bean or worker pool exists")
    void consumersWorkWithoutAnyWorker() {
        // The consumer side exists.
        assertThat(context.getBean(DlqEventConsumer.class)).isNotNull();
        assertThat(context.getBean(DltRecordConsumer.class)).isNotNull();
        assertThat(context.getBean(DeadLetterMaterializationService.class)).isNotNull();
        assertThat(context.getBean(QuarantineService.class)).isNotNull();
        assertThat(context.getBean(DlqConsumerStartupGate.class)).isNotNull();
        assertThat(context.getBeansOfType(DlqConsumerContainerController.class))
                .as("one controller per consumer, each owning its own listener")
                .hasSize(2);

        // The worker side does not.
        assertThat(context.getBeansOfType(WorkerContainerController.class))
                .as("the worker controller requires workerPool, which cannot exist with every worker disabled")
                .isEmpty();
        assertThat(context.getBeanNamesForType(DataSource.class))
                .as("no worker pool bean was created by enabling a dead-letter consumer")
                .doesNotContain("workerPool");
        assertThat(context.containsBean("emailKafkaListenerContainerFactory")).isFalse();
        assertThat(context.containsBean("webhookKafkaListenerContainerFactory")).isFalse();
        assertThat(context.containsBean("emailWorkerHealthIndicator")).isFalse();
    }

    @Test
    @DisplayName("each listener is registered under its own id and group, and neither needs the other")
    void listenersAreIndependent() {
        assertThat(registry.getListenerContainer(DlqEventConsumer.LISTENER_ID))
                .as("the authoritative consumer listener exists")
                .isNotNull();
        assertThat(registry.getListenerContainer(DltRecordConsumer.LISTENER_ID))
                .as("and the DLT consumer listener exists separately")
                .isNotNull();
        assertThat(registry.getListenerContainer(WorkerContainerController.LISTENER_ID))
                .as("no delivery-worker listener is registered")
                .isNull();

        // The position gate must survive the container factory copying its properties into each container: a gate
        // configured on the factory but absent here would leave an unresolved position unreported.
        assertThat(registry.getListenerContainer(DlqEventConsumer.LISTENER_ID)
                .getContainerProperties().getConsumerRebalanceListener())
                .as("the authoritative listener consumes only with a resolved position")
                .isInstanceOf(DlqPositionGate.class);
        assertThat(registry.getListenerContainer(DltRecordConsumer.LISTENER_ID)
                .getContainerProperties().getConsumerRebalanceListener())
                .isInstanceOf(DlqPositionGate.class);
    }

    @Test
    @DisplayName("the fixed reset policy is none, so a missing position cannot be silently skipped")
    void offsetResetPolicyIsFixed() {
        assertThat(DlqConsumerKafkaConfig.consumerProperties(
                        "localhost:9092", context.getBean(com.notify.dlq.config.DlqConsumerProperties.class))
                .get("auto.offset.reset"))
                .isEqualTo("none");
        assertThat(DltConsumerKafkaConfig.consumerProperties(
                        "localhost:9092", context.getBean(com.notify.dlq.config.DlqConsumerProperties.class))
                .get("auto.offset.reset"))
                .isEqualTo("none");
    }
}
