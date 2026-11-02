package com.notify.delivery.worker.config;

import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.delivery.worker.classifier.ErrorCategory;
import com.notify.delivery.worker.control.WorkerContainerController;
import com.notify.delivery.worker.exception.WorkerExceptions.RecoveryRefusedException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * The failure policy shared by every channel delivery worker (, generalized in ). One
 * {@link DeliveryExceptionClassifier} drives both the back-off function (how many in-place retries) and the recoverer
 * (what happens afterwards), so retry selection and recovery cannot disagree — and both channels get the identical
 * policy rather than two drifting copies.
 *
 * <p>Verified behavior of spring-kafka 4.0.7 {@code DefaultErrorHandler} with this wiring: a recoverer that returns
 * normally gets the record's offset committed ({@code commitRecovered}); a recoverer that throws makes the handler
 * re-seek the record and commit nothing. Therefore every recovery that must <em>not</em> commit (pause, stop) throws
 * {@link RecoveryRefusedException}, and the only path that returns normally is a successful dead-letter send.
 */
public final class WorkerRecoverySupport {

    /** Kafka header naming why a record was dead-lettered (headers only; the {@code DlqReason} enum is unchanged). */
    public static final String DLT_REASON_HEADER = "x-notify-dlt-reason";
    public static final String REASON_POISON_PAYLOAD = "POISON_PAYLOAD";
    public static final String REASON_UNRESOLVED_PROCESSING_STUCK = "UNRESOLVED_PROCESSING_STUCK";
    public static final String CATEGORY_HEADER = "x-notify-error-category";

    private WorkerRecoverySupport() {
    }

    public static DefaultErrorHandler buildErrorHandler(
            ConsumerRecordRecoverer recoverer, DeliveryExceptionClassifier classifier, WorkerTuning props
    ) {
        BackOff retrying = new FixedBackOff(props.retryInterval().toMillis(), props.retryAttempts());
        BackOff immediate = new FixedBackOff(0L, 0L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, retrying);
        handler.setBackOffFunction((record, ex) -> classifier.classify(ex).retries() ? retrying : immediate);
        handler.setCommitRecovered(true);
        handler.setAckAfterHandle(false);
        handler.setSeekAfterError(true);
        handler.setResetStateOnRecoveryFailure(true);
        handler.setResetStateOnExceptionChange(true);
        return handler;
    }

    public static DeadLetterPublishingRecoverer buildDeadLetterPublisher(
            KafkaTemplate<String, byte[]> template, WorkerTuning props, DeliveryExceptionClassifier classifier
    ) {
        DeadLetterPublishingRecoverer dlpr = new DeadLetterPublishingRecoverer(
                template, (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));
        dlpr.setFailIfSendResultIsError(true);
        dlpr.setWaitForSendResultTimeout(props.dltSendTimeout());
        dlpr.setThrowIfNoDestinationReturned(true);
        dlpr.setHeadersFunction((record, ex) -> {
            Headers headers = new RecordHeaders();
            ErrorCategory category = classifier.classify(ex);
            String reason = category == ErrorCategory.POISON ? REASON_POISON_PAYLOAD
                    : category == ErrorCategory.UNRESOLVED ? REASON_UNRESOLVED_PROCESSING_STUCK
                    : "UNSPECIFIED";
            headers.add(DLT_REASON_HEADER, reason.getBytes(StandardCharsets.UTF_8));
            headers.add(CATEGORY_HEADER, category.name().getBytes(StandardCharsets.UTF_8));
            return headers;
        });
        // Exception headers carry only the exception class name: never the message, the stack, or the record body.
        dlpr.setExceptionHeadersCreator((headers, exception, isKey, names) ->
                headers.add(isKey ? names.getExceptionInfo().getKeyExceptionFqcn() : names.getExceptionInfo().getExceptionFqcn(),
                        exception.getClass().getName().getBytes(StandardCharsets.UTF_8)));
        return dlpr;
    }

    /** Routes a record whose in-place retries are exhausted according to its category. */
    public static class Recoverer implements ConsumerRecordRecoverer {

        private final DeliveryExceptionClassifier classifier;
        private final WorkerContainerController controller;
        private final BiConsumer<ConsumerRecord<?, ?>, Exception> deadLetter;

        public Recoverer(
                DeliveryExceptionClassifier classifier,
                WorkerContainerController controller,
                BiConsumer<ConsumerRecord<?, ?>, Exception> deadLetter
        ) {
            this.classifier = classifier;
            this.controller = controller;
            this.deadLetter = deadLetter;
        }

        @Override
        public void accept(ConsumerRecord<?, ?> record, Exception exception) {
            ErrorCategory category = classifier.classify(exception);
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            switch (category.recoveryAction()) {
                case DEAD_LETTER -> {
                    deadLetter.accept(record, exception); // throws on send failure: nothing is committed
                    controller.onQuarantined(category == ErrorCategory.POISON
                            ? REASON_POISON_PAYLOAD : REASON_UNRESOLVED_PROCESSING_STUCK);
                }
                case PAUSE_UNTIL_HEALTHY -> {
                    controller.pauseUntilHealthy(partition);
                    throw new RecoveryRefusedException(category);
                }
                case PAUSE_WITH_BACKOFF -> {
                    controller.pauseWithBackoff(partition);
                    throw new RecoveryRefusedException(category);
                }
                case STOP -> {
                    controller.stopAsync(category + ": " + rootClass(exception));
                    throw new RecoveryRefusedException(category);
                }
            }
        }

        private static String rootClass(Throwable t) {
            Throwable root = t;
            for (int i = 0; i < 16 && root.getCause() != null && root.getCause() != root; i++) {
                root = root.getCause();
            }
            return root.getClass().getName();
        }
    }
}
