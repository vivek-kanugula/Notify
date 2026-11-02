package com.notify.delivery.worker.config;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.delivery.worker.EmailDeliveryWorker;
import com.notify.delivery.worker.InAppDeliveryWorker;
import com.notify.delivery.worker.WebhookDeliveryWorker;
import com.notify.delivery.worker.control.EmailWorkerHealthIndicator;
import com.notify.delivery.worker.control.InAppWorkerHealthIndicator;
import com.notify.delivery.worker.control.WebhookWorkerHealthIndicator;
import com.notify.delivery.worker.control.WorkerContainerController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * All eight worker activation combinations of the three channel flags (, extended by and by / ).
 *
 * <p>Enumerating them is not pedantry: the webhook-only case is the one the original build never exercised, and
 * it is exactly where the dead-letter template defect lived - the template was declared in the email-only
 * configuration, so a webhook-only deployment would have started without the bean its error handler needs. adds a
 * third flag, which turns four combinations into eight and makes the shared worker pool carry three channels
 * (). Each combination is a nested class because worker activation is a context-level decision.
 */
class WorkerActivationMatrixTest {

    @Nested
    @TestPropertySource(properties = {
            "notify.worker.email.enabled=false",
            "notify.worker.webhook.enabled=true",
            "notify.worker.webhook.auto-startup=false",
            "spring.kafka.bootstrap-servers=localhost:1"
    })
    class WebhookOnly extends AbstractPostgresIntegrationTest {

        @Autowired
        private ApplicationContext context;

        @Autowired
        private KafkaListenerEndpointRegistry registry;

        @Test
        @DisplayName("webhook only: the webhook worker exists and no email worker bean does")
        void onlyWebhookBeansExist() {
            assertThat(context.getBeansOfType(WebhookDeliveryWorker.class)).hasSize(1);
            assertThat(context.getBeansOfType(EmailDeliveryWorker.class)).isEmpty();
            assertThat(context.getBeansOfType(WebhookWorkerHealthIndicator.class)).hasSize(1);
            assertThat(context.getBeansOfType(EmailWorkerHealthIndicator.class)).isEmpty();
            assertThat(context.getBeansOfType(WorkerContainerController.class))
                    .containsOnlyKeys("webhookWorkerContainerController");
            assertThat(context.containsBean(EmailWorkerKafkaConfig.CONTAINER_FACTORY)).isFalse();
            assertThat(context.containsBean(WebhookWorkerKafkaConfig.CONTAINER_FACTORY)).isTrue();
            assertThat(registry.getListenerContainerIds())
                    .containsExactly(WorkerContainerController.WEBHOOK_LISTENER_ID);
        }

        @Test
        @DisplayName("webhook only: the shared dead-letter template, worker pool and error handler are all present")
        void sharedInfrastructureIsPresentWithoutTheEmailWorker() {
            assertThat(context.containsBean("deliveryDltKafkaTemplate")).isTrue();
            assertThat(context.getBean("deliveryDltKafkaTemplate", KafkaTemplate.class)).isNotNull();
            assertThat(context.containsBean("workerPool")).isTrue();
            assertThat(context.getBean("webhookWorkerErrorHandler", DefaultErrorHandler.class)).isNotNull();
            assertThat(context.containsBean("emailWorkerErrorHandler")).isFalse();
        }

        @Test
        @DisplayName("webhook only: health reports the webhook channel")
        void healthNamesTheChannel() {
            assertThat(context.getBean("webhookWorkerHealthIndicator", WebhookWorkerHealthIndicator.class)
                    .health().getDetails()).containsEntry("channel", "webhook");
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "notify.worker.email.enabled=true",
            "notify.worker.email.auto-startup=false",
            "notify.worker.webhook.enabled=false",
            "spring.kafka.bootstrap-servers=localhost:1"
    })
    class EmailOnly extends AbstractPostgresIntegrationTest {

        @Autowired
        private ApplicationContext context;

        @Autowired
        private KafkaListenerEndpointRegistry registry;

        @Test
        @DisplayName("email only: wiring is unchanged and no webhook worker bean exists")
        void onlyEmailBeansExist() {
            assertThat(context.getBeansOfType(EmailDeliveryWorker.class)).hasSize(1);
            assertThat(context.getBeansOfType(WebhookDeliveryWorker.class)).isEmpty();
            assertThat(context.getBeansOfType(WorkerContainerController.class))
                    .containsOnlyKeys("emailWorkerContainerController");
            assertThat(context.containsBean("deliveryDltKafkaTemplate")).isTrue();
            assertThat(context.containsBean("workerPool")).isTrue();
            assertThat(context.containsBean("webhookWorkerErrorHandler")).isFalse();
            assertThat(registry.getListenerContainerIds())
                    .containsExactly(WorkerContainerController.LISTENER_ID);
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "notify.worker.email.enabled=false",
            "notify.worker.webhook.enabled=false",
            "spring.kafka.bootstrap-servers=localhost:1"
    })
    class NeitherWorker extends AbstractPostgresIntegrationTest {

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("neither worker: no worker, controller, listener, dead-letter template or worker pool exists")
        void nothingWorkerRelatedExists() {
            assertThat(context.getBeansOfType(EmailDeliveryWorker.class)).isEmpty();
            assertThat(context.getBeansOfType(WebhookDeliveryWorker.class)).isEmpty();
            assertThat(context.getBeansOfType(WorkerContainerController.class)).isEmpty();
            assertThat(context.containsBean("deliveryDltKafkaTemplate")).isFalse();
            assertThat(context.containsBean("workerPool"))
                    .as("the shared pool is conditional on any worker").isFalse();
            assertThat(context.getBeansOfType(InAppDeliveryWorker.class)).isEmpty();
            assertThat(context.getBeansOfType(KafkaListenerEndpointRegistry.class).isEmpty()
                    || context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainerIds().isEmpty())
                    .isTrue();
        }
    }

    // ─── / the four combinations the third flag adds ────────────────────────────────────────────────────

    @Nested
    @TestPropertySource(properties = {
            "notify.worker.email.enabled=false",
            "notify.worker.webhook.enabled=false",
            "notify.worker.inapp.enabled=true",
            "notify.worker.inapp.auto-startup=false",
            "spring.kafka.bootstrap-servers=localhost:1"
    })
    class InAppOnly extends AbstractPostgresIntegrationTest {

        @Autowired private ApplicationContext context;
        @Autowired private KafkaListenerEndpointRegistry registry;

        @Test
        @DisplayName("in-app only: its worker exists, no other channel's does, and the shared infrastructure is present")
        void onlyInAppBeansExist() {
            assertThat(context.getBeansOfType(InAppDeliveryWorker.class)).hasSize(1);
            assertThat(context.getBeansOfType(EmailDeliveryWorker.class)).isEmpty();
            assertThat(context.getBeansOfType(WebhookDeliveryWorker.class)).isEmpty();
            assertThat(context.getBeansOfType(InAppWorkerHealthIndicator.class)).hasSize(1);
            assertThat(context.getBeansOfType(WorkerContainerController.class))
                    .containsOnlyKeys("inappWorkerContainerController");
            assertThat(registry.getListenerContainerIds())
                    .containsExactly(WorkerContainerController.INAPP_LISTENER_ID);
            // The same trap the webhook-only case exposed in these are shared beans, and a single-channel
            // deployment must still get them.
            assertThat(context.containsBean("deliveryDltKafkaTemplate")).isTrue();
            assertThat(context.containsBean("workerPool")).isTrue();
            assertThat(context.getBean("inappWorkerErrorHandler", DefaultErrorHandler.class)).isNotNull();
            assertThat(context.containsBean("emailWorkerErrorHandler")).isFalse();
            assertThat(context.containsBean("webhookWorkerErrorHandler")).isFalse();
        }

        @Test
        @DisplayName("in-app only: no webhook encryption key is needed to run this channel")
        void needsNoWebhookSecret() {
            // The webhook startup guard is a webhook concern. An in-app-only deployment must not inherit it.
            assertThat(context.getBeansOfType(InAppDeliveryWorker.class)).hasSize(1);
            assertThat(context.getBean("inappWorkerHealthIndicator", InAppWorkerHealthIndicator.class)
                    .health().getDetails()).containsEntry("channel", "inapp");
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "notify.worker.email.enabled=true",
            "notify.worker.email.auto-startup=false",
            "notify.worker.webhook.enabled=false",
            "notify.worker.inapp.enabled=true",
            "notify.worker.inapp.auto-startup=false",
            "spring.kafka.bootstrap-servers=localhost:1"
    })
    class EmailAndInApp extends AbstractPostgresIntegrationTest {

        @Autowired private ApplicationContext context;
        @Autowired private KafkaListenerEndpointRegistry registry;

        @Test
        @DisplayName("email + in-app: both workers, one shared pool, and no webhook bean")
        void twoChannelsShareOnePool() {
            assertThat(context.getBeansOfType(EmailDeliveryWorker.class)).hasSize(1);
            assertThat(context.getBeansOfType(InAppDeliveryWorker.class)).hasSize(1);
            assertThat(context.getBeansOfType(WebhookDeliveryWorker.class)).isEmpty();
            assertThat(context.getBeansOfType(WorkerContainerController.class))
                    .containsOnlyKeys("emailWorkerContainerController", "inappWorkerContainerController");
            assertThat(registry.getListenerContainerIds()).containsExactlyInAnyOrder(
                    WorkerContainerController.LISTENER_ID, WorkerContainerController.INAPP_LISTENER_ID);
            assertThat(context.getBeansOfType(javax.sql.DataSource.class).keySet())
                    .as("/one shared worker pool, never one per channel")
                    .contains("workerPool");
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "notify.worker.email.enabled=false",
            "notify.worker.webhook.enabled=true",
            "notify.worker.webhook.auto-startup=false",
            "notify.worker.inapp.enabled=true",
            "notify.worker.inapp.auto-startup=false",
            "spring.kafka.bootstrap-servers=localhost:1"
    })
    class WebhookAndInApp extends AbstractPostgresIntegrationTest {

        @Autowired private ApplicationContext context;
        @Autowired private KafkaListenerEndpointRegistry registry;

        @Test
        @DisplayName("webhook + in-app: both workers and no email bean")
        void bothChannelsExist() {
            assertThat(context.getBeansOfType(WebhookDeliveryWorker.class)).hasSize(1);
            assertThat(context.getBeansOfType(InAppDeliveryWorker.class)).hasSize(1);
            assertThat(context.getBeansOfType(EmailDeliveryWorker.class)).isEmpty();
            assertThat(registry.getListenerContainerIds()).containsExactlyInAnyOrder(
                    WorkerContainerController.WEBHOOK_LISTENER_ID, WorkerContainerController.INAPP_LISTENER_ID);
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "notify.worker.email.enabled=true",
            "notify.worker.email.auto-startup=false",
            "notify.worker.webhook.enabled=true",
            "notify.worker.webhook.auto-startup=false",
            "notify.worker.inapp.enabled=true",
            "notify.worker.inapp.auto-startup=false",
            "spring.kafka.bootstrap-servers=localhost:1"
    })
    class AllThreeWorkers extends AbstractPostgresIntegrationTest {

        @Autowired private ApplicationContext context;
        @Autowired private KafkaListenerEndpointRegistry registry;

        @Test
        @DisplayName("all three: three workers, three controllers, three listeners, one shared pool ()")
        void allThreeChannelsCoexist() {
            assertThat(context.getBeansOfType(EmailDeliveryWorker.class)).hasSize(1);
            assertThat(context.getBeansOfType(WebhookDeliveryWorker.class)).hasSize(1);
            assertThat(context.getBeansOfType(InAppDeliveryWorker.class)).hasSize(1);
            assertThat(context.getBeansOfType(WorkerContainerController.class)).containsOnlyKeys(
                    "emailWorkerContainerController",
                    "webhookWorkerContainerController",
                    "inappWorkerContainerController");
            assertThat(registry.getListenerContainerIds()).containsExactlyInAnyOrder(
                    WorkerContainerController.LISTENER_ID,
                    WorkerContainerController.WEBHOOK_LISTENER_ID,
                    WorkerContainerController.INAPP_LISTENER_ID);
            assertThat(context.containsBean("workerPool")).isTrue();
        }

        @Test
        @DisplayName("all three: the shared worker pool is sized 14, the value for three channels")
        void poolIsSizedForThreeChannels() {
            com.zaxxer.hikari.HikariDataSource pool =
                    context.getBean("workerPool", com.zaxxer.hikari.HikariDataSource.class);
            assertThat(pool.getMaximumPoolSize())
                    .as("3 listener threads + 1 health poll per enabled worker + 2 headroom")
                    .isEqualTo(14);
        }
    }
}
