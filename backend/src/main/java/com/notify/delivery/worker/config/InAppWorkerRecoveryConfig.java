package com.notify.delivery.worker.config;

import com.notify.common.config.ConditionalOnInAppWorker;
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
 * Error handler and recoverer beans of the in-app worker (). The policy comes from {@link WorkerRecoverySupport},
 * identical to the other channels', so dead-lettering and commit ordering cannot drift. The dead-letter topic is
 * derived from the record's own topic, so in-app records go to {@code notify.dispatch.inapp.v1.DLT}.
 */
@Configuration
@ConditionalOnInAppWorker
public class InAppWorkerRecoveryConfig {

    @Bean
    public DeadLetterPublishingRecoverer inappDeadLetterPublisher(
            @Qualifier("deliveryDltKafkaTemplate") KafkaTemplate<String, byte[]> dltTemplate,
            InAppWorkerProperties props,
            DeliveryExceptionClassifier classifier
    ) {
        return WorkerRecoverySupport.buildDeadLetterPublisher(dltTemplate, props, classifier);
    }

    @Bean
    public ConsumerRecordRecoverer inappWorkerRecoverer(
            DeliveryExceptionClassifier classifier,
            @Qualifier("inappWorkerContainerController") WorkerContainerController controller,
            @Qualifier("inappDeadLetterPublisher") DeadLetterPublishingRecoverer inappDeadLetterPublisher
    ) {
        return new WorkerRecoverySupport.Recoverer(classifier, controller, inappDeadLetterPublisher);
    }

    @Bean
    public DefaultErrorHandler inappWorkerErrorHandler(
            @Qualifier("inappWorkerRecoverer") ConsumerRecordRecoverer inappWorkerRecoverer,
            DeliveryExceptionClassifier classifier,
            InAppWorkerProperties props
    ) {
        return WorkerRecoverySupport.buildErrorHandler(inappWorkerRecoverer, classifier, props);
    }
}