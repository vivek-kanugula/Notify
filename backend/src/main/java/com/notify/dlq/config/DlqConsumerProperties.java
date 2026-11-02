package com.notify.dlq.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Dead-letter consumer and admin-API settings ({@code notify.dlq.*}, / , ).
 *
 * <p><b>Both consumers are disabled by default</b>, in every profile, exactly as every delivery worker is
 * (). Adding therefore cannot change the behaviour of an existing deployment until it is switched on,
 * and switching it on is a separately authorized action.
 *
 * <p><b>{@code auto.offset.reset} is not here on purpose.</b> It is fixed at {@code none} in the consumer factory
 * and is not tunable: a listener resumes valid committed offsets, and a missing or out-of-range position stops the
 * listener with actionable evidence instead of silently skipping or silently replaying ().
 */
@ConfigurationProperties(prefix = "notify.dlq")
public record DlqConsumerProperties(
        @DefaultValue Consumer consumer,
        @DefaultValue DltConsumer dltConsumer,
        /**
         * Path to the reviewed offset-initialization input. **Unset means no initializer runs at all**, and an
         * absent committed position then stops the listener (requirement 4 of ).
         */
        String offsetInitFile,
        @DefaultValue Api api
) {

    public boolean anyConsumerEnabled() {
        return consumer.enabled() || dltConsumer.enabled();
    }

    public boolean offsetInitializationConfigured() {
        return offsetInitFile != null && !offsetInitFile.isBlank();
    }

    public record Consumer(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("notify-dlq-consumer") String groupId,
            /**
             * The authoritative dead-letter topic the listener subscribes to, and the set the offset-initialization
             * input is restricted to.
             *
             * <p>Overridable so an acceptance run can own a topic of its own. <b>The producer side is not
             * configurable</b>: { NotificationService.recordDeadLetter} writes { KafkaTopicsConfig.TOPIC_DLQ},
             * so overriding this in a deployment would subscribe the consumer to a topic nothing writes to. A
             * deployment leaves it at the default.
             */
            @DefaultValue("notify.dlq.v1") String topic,
            @DefaultValue("10") int maxPollRecords,
            @DefaultValue("1") int concurrency,
            @DefaultValue("500ms") Duration retryInterval,
            @DefaultValue("3") int retryAttempts,
            @DefaultValue("5s") Duration pauseInitial,
            @DefaultValue("1m") Duration pauseMax
    ) {
        public Consumer {
            if (groupId == null || groupId.isBlank()) {
                throw new IllegalArgumentException("notify.dlq.consumer.group-id must not be blank");
            }
            if (topic == null || topic.isBlank()) {
                throw new IllegalArgumentException("notify.dlq.consumer.topic must not be blank");
            }
            if (maxPollRecords < 1 || maxPollRecords > 100) {
                throw new IllegalArgumentException("notify.dlq.consumer.max-poll-records must be between 1 and 100");
            }
            if (concurrency < 1) {
                throw new IllegalArgumentException("notify.dlq.consumer.concurrency must be positive");
            }
            if (retryAttempts < 0) {
                throw new IllegalArgumentException("notify.dlq.consumer.retry-attempts must not be negative");
            }
            if (pauseInitial == null || pauseInitial.isZero() || pauseInitial.isNegative()
                    || pauseMax == null || pauseMax.compareTo(pauseInitial) < 0) {
                throw new IllegalArgumentException(
                        "notify.dlq.consumer.pause-initial must be positive and pause-max must not be smaller");
            }
        }
    }

    public record DltConsumer(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("notify-dlt-consumer") String groupId,
            @DefaultValue("10") int maxPollRecords,
            @DefaultValue("1") int concurrency,
            @DefaultValue("500ms") Duration retryInterval,
            @DefaultValue("3") int retryAttempts,
            @DefaultValue("5s") Duration pauseInitial,
            @DefaultValue("1m") Duration pauseMax
    ) {
        public DltConsumer {
            if (groupId == null || groupId.isBlank()) {
                throw new IllegalArgumentException("notify.dlq.dlt-consumer.group-id must not be blank");
            }
            if (maxPollRecords < 1 || maxPollRecords > 100) {
                throw new IllegalArgumentException(
                        "notify.dlq.dlt-consumer.max-poll-records must be between 1 and 100");
            }
            if (concurrency < 1) {
                throw new IllegalArgumentException("notify.dlq.dlt-consumer.concurrency must be positive");
            }
        }
    }

    /** Bounds on the admin surface, so a projection cannot be asked for an unbounded page. */
    public record Api(
            @DefaultValue("50") int defaultLimit,
            @DefaultValue("200") int maxLimit,
            @DefaultValue("20") int detailEmbeddedLimit
    ) {
        public Api {
            if (defaultLimit < 1 || maxLimit < defaultLimit || maxLimit > 500) {
                throw new IllegalArgumentException(
                        "notify.dlq.api limits must satisfy 1 <= default-limit <= max-limit <= 500");
            }
            if (detailEmbeddedLimit < 1 || detailEmbeddedLimit > 100) {
                throw new IllegalArgumentException(
                        "notify.dlq.api.detail-embedded-limit must be between 1 and 100");
            }
        }
    }
}
