package com.notify.delivery.worker.config;

import java.time.Duration;

/**
 * Tuning common to every channel delivery worker (). The failure policy, the consumer settings and
 * the health thresholds must behave identically for each channel, so the controller, the error handler and the
 * health indicator are written against this interface and each channel supplies its own property record.
 */
public interface WorkerTuning {

    /** Short channel name used in bean names, listener ids, metric tags and the health component. */
    String name();

    boolean enabled();

    String topic();

    String groupId();

    boolean autoStartup();

    /** In-place retry interval and count for the categories that retry. */
    Duration retryInterval();

    int retryAttempts();

    /** Timed partition pause with escalation (exhausted contention, ordering, broker-transient). */
    Duration pauseInitial();

    Duration pauseMax();

    int escalateAfter();

    /** Connectivity pause: resume after this many consecutive successful database probes. */
    Duration healthPollInterval();

    int healthSuccesses();

    /** Bound applied to a dead-letter send; the offset is committed only after it succeeds. */
    Duration dltSendTimeout();

    /** health thresholds. */
    long lagThreshold();

    Duration assignmentGrace();
}
