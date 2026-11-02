package com.notify.delivery.worker.classifier;

/**
 * Failure categories of the delivery worker (plan section 8.2). One category decides both how many in-container
 * retries a record gets and what happens when they are exhausted, so retry selection and recovery can never diverge.
 */
public enum ErrorCategory {

    /** Database unreachable or connection lost: no retries; pause the partition until the database answers again. */
    CONNECTIVITY(false, RecoveryAction.PAUSE_UNTIL_HEALTHY),
    /** Pool exhausted, lock or statement timeout: bounded retries, then a timed partition pause with escalation. */
    CONTENTION(true, RecoveryAction.PAUSE_WITH_BACKOFF),
    /** Event ahead of, or racing, the database state: bounded retries, then a timed partition pause. */
    ORDERING(true, RecoveryAction.PAUSE_WITH_BACKOFF),
    /** Kafka client transient failure (including acknowledgment): bounded retries, then a timed partition pause. */
    BROKER_TRANSIENT(true, RecoveryAction.PAUSE_WITH_BACKOFF),
    /** Row still PROCESSING after bounded retries: dead-letter the record (header reason UNRESOLVED_PROCESSING_STUCK). */
    UNRESOLVED(true, RecoveryAction.DEAD_LETTER),
    /** Malformed payload: dead-letter immediately (header reason POISON_PAYLOAD). */
    POISON(false, RecoveryAction.DEAD_LETTER),
    /** Schema, configuration or authentication fault: stop the container; offset retained. */
    NON_TRANSIENT(false, RecoveryAction.STOP),
    /** Anything not recognized: stop the container rather than guess; offset retained. */
    UNCLASSIFIED(false, RecoveryAction.STOP);

    /** What the recoverer does once the container's bounded retries (if any) are exhausted. */
    public enum RecoveryAction {
        PAUSE_UNTIL_HEALTHY,
        PAUSE_WITH_BACKOFF,
        DEAD_LETTER,
        STOP
    }

    private final boolean retries;
    private final RecoveryAction recoveryAction;

    ErrorCategory(boolean retries, RecoveryAction recoveryAction) {
        this.retries = retries;
        this.recoveryAction = recoveryAction;
    }

    /** Whether the container retries the record in place (fixed back-off) before recovery. */
    public boolean retries() {
        return retries;
    }

    public RecoveryAction recoveryAction() {
        return recoveryAction;
    }
}
