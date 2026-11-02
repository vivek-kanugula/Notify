package com.notify.dlq.consumer;

import com.notify.dlq.config.DlqConsumerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The accepted {@code notify.dlq} defaults and bounds (/ , ; part of ).
 *
 * <p>The defaults carry a decision, so they are asserted rather than trusted: **both consumers disabled**, and
 * **no offset initialization input**, which is what makes "a missing position stops the listener" the ordinary
 * behaviour instead of a seek.
 */
class DlqConsumerPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DlqConsumerProperties.class)
    static class Harness {
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(Harness.class);

    @Test
    @DisplayName("the accepted defaults: both consumers off, no initialization input, bounded API limits")
    void acceptedDefaults() {
        runner.run(context -> {
            DlqConsumerProperties props = context.getBean(DlqConsumerProperties.class);
            assertThat(props.consumer().enabled()).isFalse();
            assertThat(props.dltConsumer().enabled()).isFalse();
            assertThat(props.anyConsumerEnabled()).isFalse();
            assertThat(props.offsetInitializationConfigured())
                    .as("no input means no initializer runs at all (requirement 4)")
                    .isFalse();
            assertThat(props.consumer().groupId()).isEqualTo("notify-dlq-consumer");
            assertThat(props.dltConsumer().groupId()).isEqualTo("notify-dlt-consumer");
            assertThat(props.consumer().maxPollRecords()).isEqualTo(10);
            assertThat(props.api().defaultLimit()).isEqualTo(50);
            assertThat(props.api().maxLimit()).isEqualTo(200);
            assertThat(props.api().detailEmbeddedLimit()).isEqualTo(20);
        });
    }

    @Test
    @DisplayName("each bound is validated independently, and a blank group id or topic is refused")
    void boundsAreValidated() {
        assertThatThrownBy(() -> new DlqConsumerProperties.Consumer(
                true, "  ", "notify.dlq.v1", 10, 1, Duration.ofMillis(500), 3, Duration.ofSeconds(5),
                Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("group-id");
        assertThatThrownBy(() -> new DlqConsumerProperties.Consumer(
                true, "g", " ", 10, 1, Duration.ofMillis(500), 3, Duration.ofSeconds(5),
                Duration.ofMinutes(1)))
                .as("a blank topic would make the listener subscribe to nothing silently")
                .hasMessageContaining("topic");
        assertThatThrownBy(() -> new DlqConsumerProperties.Consumer(
                true, "g", "notify.dlq.v1", 0, 1, Duration.ofMillis(500), 3, Duration.ofSeconds(5),
                Duration.ofMinutes(1)))
                .hasMessageContaining("max-poll-records");
        assertThatThrownBy(() -> new DlqConsumerProperties.Consumer(
                true, "g", "notify.dlq.v1", 10, 1, Duration.ofMillis(500), 3, Duration.ofMinutes(1),
                Duration.ofSeconds(5)))
                .as("pause-max below pause-initial would make the escalation meaningless")
                .hasMessageContaining("pause-initial");
        assertThatThrownBy(() -> new DlqConsumerProperties.Api(50, 10, 20))
                .hasMessageContaining("max-limit");
        assertThatThrownBy(() -> new DlqConsumerProperties.Api(50, 200, 0))
                .hasMessageContaining("detail-embedded-limit");
    }

    @Test
    @DisplayName("an explicitly configured input is what makes the initializer run")
    void initializationInputIsOptIn() {
        runner.withPropertyValues("notify.dlq.offset-init-file=/tmp/offsets.json").run(context -> {
            DlqConsumerProperties props = context.getBean(DlqConsumerProperties.class);
            assertThat(props.offsetInitializationConfigured()).isTrue();
            assertThat(props.offsetInitFile()).isEqualTo("/tmp/offsets.json");
        });
    }

}
