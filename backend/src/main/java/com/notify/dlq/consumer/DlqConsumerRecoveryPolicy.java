package com.notify.dlq.consumer;

import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.delivery.worker.classifier.ErrorCategory;
import com.notify.dlq.config.DlqConsumerProperties;
import org.apache.kafka.clients.consumer.InvalidOffsetException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * The failure policy for a dead-letter consumer (/ , , decision ).
 *
 * <p><b>Nothing is ever committed by a recovery.</b> The delivery workers can dead-letter a record they cannot
 * process; these consumers cannot - they <em>are</em> the dead-letter path, and there is deliberately no
 * dead-letter topic for the dead-letter topic. So every recovery here throws, which makes spring-kafka re-seek the
 * record and commit nothing: a record that could not be recorded durably stays uncommitted and is retried after the
 * pause, or waits for an operator if the consumer was stopped.
 *
 * <p>The classification itself is reused from the delivery workers ({@link DeliveryExceptionClassifier}), which is
 * a pure component with no worker dependency, so the two paths cannot drift in how they read a database failure.
 */
public final class DlqConsumerRecoveryPolicy {

    private DlqConsumerRecoveryPolicy() {
    }

    public static DefaultErrorHandler errorHandler(
            DeliveryExceptionClassifier classifier,
            DlqConsumerContainerController controller,
            DlqConsumerProperties.Consumer tuning
    ) {
        return build(classifier, controller, tuning.retryInterval().toMillis(), tuning.retryAttempts());
    }

    public static DefaultErrorHandler errorHandler(
            DeliveryExceptionClassifier classifier,
            DlqConsumerContainerController controller,
            DlqConsumerProperties.DltConsumer tuning
    ) {
        return build(classifier, controller, tuning.retryInterval().toMillis(), tuning.retryAttempts());
    }

    private static DefaultErrorHandler build(
            DeliveryExceptionClassifier classifier,
            DlqConsumerContainerController controller,
            long retryIntervalMillis,
            int retryAttempts
    ) {
        FixedBackOff retrying = new FixedBackOff(retryIntervalMillis, retryAttempts);
        FixedBackOff immediate = new FixedBackOff(0L, 0L);

        DefaultErrorHandler handler = new UnresolvedPositionAwareErrorHandler(controller, (record, exception) -> {
            ErrorCategory category = classifier.classify(exception);
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            switch (category.recoveryAction()) {
                case STOP, DEAD_LETTER ->
                        // DEAD_LETTER has no meaning here, so it is treated as what it actually is: a record this
                        // consumer cannot record, which needs an operator rather than a quiet commit.
                        controller.stop(category + ": " + exception.getClass().getName());
                case PAUSE_UNTIL_HEALTHY, PAUSE_WITH_BACKOFF -> controller.pauseWithBackoff(partition, category);
            }
            // Always: nothing is committed, the record is re-seeked.
            throw new DlqRecoveryRefusedException(category, exception);
        }, retrying);

        handler.setBackOffFunction((record, exception) ->
                classifier.classify(exception).retries() ? retrying : immediate);
        handler.setCommitRecovered(false);
        handler.setAckAfterHandle(false);
        handler.setSeekAfterError(true);
        handler.setResetStateOnRecoveryFailure(true);
        handler.setResetStateOnExceptionChange(true);
        return handler;
    }

    /**
     * Turns an <b>unresolved offset position</b> into a stopped listener with actionable evidence (,
     * requirement 1; to ).
     *
     * <p>A position failure arrives on the poll, not on a record, so it reaches { handleOtherException} and
     * never the record recoverer. Measured against the resolved spring-kafka 4.0.7 and kafka-clients 4.1.2:
     * { NoOffsetForPartitionException} is already fatal to the container, but
     * <b>{ OffsetOutOfRangeException} is not</b> - the poll loop raises it again on every attempt and the
     * listener stays up, logging forever. An unresolved position must be a stopped listener, so it is handled here
     * explicitly. Nothing is seeked and nothing is committed: the stored offset stays exactly as an operator left
     * it, and repairing it is separately authorized recovery.
     */
    private static final class UnresolvedPositionAwareErrorHandler extends DefaultErrorHandler {

        private final DlqConsumerContainerController controller;

        private UnresolvedPositionAwareErrorHandler(
                DlqConsumerContainerController controller,
                org.springframework.kafka.listener.ConsumerRecordRecoverer recoverer,
                org.springframework.util.backoff.BackOff backOff
        ) {
            super(recoverer, backOff);
            this.controller = controller;
        }

        @Override
        public void handleOtherException(
                Exception thrownException,
                org.apache.kafka.clients.consumer.Consumer<?, ?> consumer,
                org.springframework.kafka.listener.MessageListenerContainer container,
                boolean batchListener
        ) {
            InvalidOffsetException unresolved = unresolvedPosition(thrownException);
            if (unresolved != null) {
                controller.stop("UNRESOLVED_OFFSET_POSITION: " + unresolved.getClass().getSimpleName()
                        + " for " + unresolved.partitions() + " in group " + groupIdOf(container)
                        + rangeDetail(unresolved, consumer)
                        + "; no seek and no commit was performed, initialize the position explicitly");
                return;
            }
            super.handleOtherException(thrownException, consumer, container, batchListener);
        }

        /**
         * The committed offset and the current retained range, which is what CONTRACTS requires an out-of-range
         * stop to name. The fetch offsets come from the exception itself; the range is read from the consumer that
         * raised it, inside its own configured api timeout, and a failed read degrades the message rather than the
         * stop.
         */
        private static String rangeDetail(
                InvalidOffsetException unresolved, org.apache.kafka.clients.consumer.Consumer<?, ?> consumer) {
            if (!(unresolved instanceof org.apache.kafka.clients.consumer.OffsetOutOfRangeException outOfRange)) {
                return "";
            }
            java.util.Map<org.apache.kafka.common.TopicPartition, Long> fetchOffsets =
                    outOfRange.offsetOutOfRangePartitions();
            StringBuilder detail = new StringBuilder();
            for (java.util.Map.Entry<org.apache.kafka.common.TopicPartition, Long> entry : fetchOffsets.entrySet()) {
                detail.append("; ").append(entry.getKey())
                        .append(" committed at ").append(entry.getValue())
                        .append(", retained range [").append(bound(consumer, entry.getKey(), true))
                        .append(", ").append(bound(consumer, entry.getKey(), false)).append("]");
            }
            return detail.toString();
        }

        private static String bound(
                org.apache.kafka.clients.consumer.Consumer<?, ?> consumer,
                org.apache.kafka.common.TopicPartition partition,
                boolean earliest) {
            try {
                java.util.Map<org.apache.kafka.common.TopicPartition, Long> offsets = earliest
                        ? consumer.beginningOffsets(java.util.List.of(partition))
                        : consumer.endOffsets(java.util.List.of(partition));
                Long value = offsets.get(partition);
                return value == null ? "unavailable" : value.toString();
            } catch (RuntimeException e) {
                return "unavailable";
            }
        }
        private static InvalidOffsetException unresolvedPosition(Throwable thrown) {
            // A bounded walk: a cause chain can be cyclic, and this runs on the listener thread.
            Throwable current = thrown;
            for (int depth = 0; current != null && depth < 10; depth++) {
                if (current instanceof InvalidOffsetException invalid) {
                    return invalid;
                }
                Throwable cause = current.getCause();
                current = cause == current ? null : cause;
            }
            return null;
        }

        private static String groupIdOf(org.springframework.kafka.listener.MessageListenerContainer container) {
            String groupId = container == null ? null : container.getGroupId();
            return groupId == null ? "unknown" : groupId;
        }
    }

    /** Signals that the recovery deliberately refused to commit. */
    public static class DlqRecoveryRefusedException extends RuntimeException {

        private final ErrorCategory category;

        public DlqRecoveryRefusedException(ErrorCategory category, Throwable cause) {
            super(category.name(), cause);
            this.category = category;
        }

        public ErrorCategory category() {
            return category;
        }
    }
}
