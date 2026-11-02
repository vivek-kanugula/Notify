package com.notify.delivery.worker.config;

import com.notify.common.config.ConditionalOnEmailWorker;
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
 * Error handler and recoverer beans of the email worker (plan section 8.3). The policy itself lives in
 * {@link WorkerRecoverySupport}, shared with every other channel worker since , so a change to the dead-letter or
 * commit-ordering rules cannot apply to one channel only.
 *
 * <p>The header reason constants are re-exported here because CONTRACTS and the evidence refer to them by this
 * class; they are the same values.
 */
@Configuration
@ConditionalOnEmailWorker
public class EmailWorkerRecoveryConfig {

    public static final String DLT_REASON_HEADER = WorkerRecoverySupport.DLT_REASON_HEADER;
    public static final String REASON_POISON_PAYLOAD = WorkerRecoverySupport.REASON_POISON_PAYLOAD;
    public static final String REASON_UNRESOLVED_PROCESSING_STUCK = WorkerRecoverySupport.REASON_UNRESOLVED_PROCESSING_STUCK;
    public static final String CATEGORY_HEADER = WorkerRecoverySupport.CATEGORY_HEADER;

    @Bean
    public DeadLetterPublishingRecoverer emailDeadLetterPublisher(
            @Qualifier("deliveryDltKafkaTemplate") KafkaTemplate<String, byte[]> dltTemplate,
            EmailWorkerProperties props,
            DeliveryExceptionClassifier classifier
    ) {
        return WorkerRecoverySupport.buildDeadLetterPublisher(dltTemplate, props, classifier);
    }

    @Bean
    public ConsumerRecordRecoverer emailWorkerRecoverer(
            DeliveryExceptionClassifier classifier,
            @Qualifier("emailWorkerContainerController") WorkerContainerController controller,
            DeadLetterPublishingRecoverer emailDeadLetterPublisher
    ) {
        return new WorkerRecoverySupport.Recoverer(classifier, controller, emailDeadLetterPublisher);
    }

    @Bean
    public DefaultErrorHandler emailWorkerErrorHandler(
            @Qualifier("emailWorkerRecoverer") ConsumerRecordRecoverer emailWorkerRecoverer,
            DeliveryExceptionClassifier classifier,
            EmailWorkerProperties props
    ) {
        return WorkerRecoverySupport.buildErrorHandler(emailWorkerRecoverer, classifier, props);
    }
}
