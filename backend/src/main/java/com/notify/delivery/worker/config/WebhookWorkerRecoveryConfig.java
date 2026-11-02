package com.notify.delivery.worker.config;

import com.notify.common.config.ConditionalOnWebhookWorker;
import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.delivery.worker.control.WorkerContainerController;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * Error handler and recoverer beans of the webhook worker (). The policy comes from
 * {@link WorkerRecoverySupport}, identical to the email worker's, so dead-lettering and commit ordering cannot drift
 * between channels. The dead-letter topic is derived from the record's own topic, so webhook records go to
 * {@code notify.dispatch.webhook.v1.DLT}.
 *
 * <p>The byte-array Kafka template is shared with the email worker: it carries no channel-specific configuration and
 * only ever publishes a byte-identical copy of the original record.
 */
@Configuration
@ConditionalOnWebhookWorker
public class WebhookWorkerRecoveryConfig {

    @Bean
    public DeadLetterPublishingRecoverer webhookDeadLetterPublisher(
            @Qualifier("deliveryDltKafkaTemplate") KafkaTemplate<String, byte[]> dltTemplate,
            WebhookWorkerProperties props,
            DeliveryExceptionClassifier classifier
    ) {
        return WorkerRecoverySupport.buildDeadLetterPublisher(dltTemplate, props, classifier);
    }

    @Bean
    public ConsumerRecordRecoverer webhookWorkerRecoverer(
            DeliveryExceptionClassifier classifier,
            @Qualifier("webhookWorkerContainerController") WorkerContainerController controller,
            @Qualifier("webhookDeadLetterPublisher") DeadLetterPublishingRecoverer webhookDeadLetterPublisher
    ) {
        return new WorkerRecoverySupport.Recoverer(classifier, controller, webhookDeadLetterPublisher);
    }

    @Bean
    public DefaultErrorHandler webhookWorkerErrorHandler(
            @Qualifier("webhookWorkerRecoverer") ConsumerRecordRecoverer webhookWorkerRecoverer,
            DeliveryExceptionClassifier classifier,
            WebhookWorkerProperties props
    ) {
        return WorkerRecoverySupport.buildErrorHandler(webhookWorkerRecoverer, classifier, props);
    }
}
