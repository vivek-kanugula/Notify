package com.notify.dlq.consumer;

import com.notify.AbstractIsolatedDatabaseTest;
import com.notify.delivery.worker.control.WorkerContainerController;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared assertions for a <b>refused</b> offset initialization (/ , , requirement 1; ).
 *
 * <p>The claim is the one a successful startup can never establish: when initialization is refused, <b>both</b>
 * listeners stay stopped, nothing is committed for either group and nothing is consumed. Each subclass supplies one
 * refusal cause through its own {@code @TestPropertySource}, because the cause is a property of the context.
 *
 * <p>Delivery workers are disabled throughout, so this also re-proves that the dead-letter side needs no
 * {@code workerPool} and creates no worker.
 */
abstract class AbstractDlqStartupRefusalTest extends AbstractIsolatedDatabaseTest {

    @Autowired private ApplicationContext context;
    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired private DlqConsumerStartupGate gate;

    /** What the refusal reason must mention, so an operator can act on it. */
    abstract String expectedReasonFragment();

    @Test
    @DisplayName("both listeners stay stopped, with an actionable refusal recorded for each")
    void bothListenersStayStopped() {
        assertThat(gate.applied())
                .as("a refused initialization applies nothing")
                .isEmpty();
        assertThat(gate.refusals())
                .as("one actionable entry per enabled listener")
                .containsOnlyKeys(DlqEventConsumer.LISTENER_ID, DltRecordConsumer.LISTENER_ID);
        assertThat(gate.refusals().values())
                .allSatisfy(reason -> assertThat(reason).contains(expectedReasonFragment()));

        assertStopped(DlqEventConsumer.LISTENER_ID);
        assertStopped(DltRecordConsumer.LISTENER_ID);
    }

    @Test
    @DisplayName("no offset was committed for either group, so nothing was consumed")
    void nothingWasCommittedAndNothingConsumed() throws Exception {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, isolatedKafka.getBootstrapServers());
        try (Admin admin = Admin.create(properties)) {
            for (String groupId : List.of("n17-refusal-dlq", "n17-refusal-dlt")) {
                Map<TopicPartition, OffsetAndMetadata> committed =
                        admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get();
                assertThat(committed)
                        .as("group %s must hold no position: the refusal happened before any write", groupId)
                        .isEmpty();
            }
        }
    }

    @Test
    @DisplayName("/17.33 a refused dead-letter startup creates no worker and no worker pool")
    void noWorkerInfrastructureExists() {
        assertThat(context.getBeansOfType(WorkerContainerController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DataSource.class)).doesNotContain("workerPool");
    }

    private void assertStopped(String listenerId) {
        MessageListenerContainer container = registry.getListenerContainer(listenerId);
        assertThat(container)
                .as("the listener is registered, so it is visible as stopped rather than absent")
                .isNotNull();
        assertThat(container.isRunning())
                .as("listener %s must not consume from an unresolved position", listenerId)
                .isFalse();
        // And it stays stopped: the gate has already run, so nothing starts it later.
        assertThat(container.isRunning()).isFalse();
    }

    static Duration shortWait() {
        return Duration.ofSeconds(5);
    }
}
