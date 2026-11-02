package com.notify.delivery.model;

import com.notify.common.model.AttemptOutcome;
import com.notify.common.model.DlqReason;

import java.time.Instant;

/**
 * The decided, ready-to-persist result of one delivery attempt. Produced by the orchestrator (no transaction held)
 * and written in one atomic transaction by {@code DeliveryResultService}. Every variant carries the attempt window.
 */
public sealed interface PersistableOutcome {

    Instant startedAt();

    Instant finishedAt();

    /** Provider accepted the message. */
    record Delivered(Instant startedAt, Instant finishedAt, String provider, String messageId) implements PersistableOutcome {
    }

    /** Retryable failure with retry budget left; the notification goes to RETRYING. */
    record Retry(
            Instant startedAt, Instant finishedAt, String provider,
            String errorCode, String errorMessage, Integer httpStatus, String snippet,
            Instant nextRetryAt
    ) implements PersistableOutcome {
    }

    /**
     * Terminal failure: permanent, tenant not active, or retries exhausted. {@code attemptOutcome} is
     * RETRYABLE_FAILURE for exhaustion and PERMANENT_FAILURE otherwise; a DLQ outbox event is always written.
     */
    record Fail(
            Instant startedAt, Instant finishedAt, String provider,
            AttemptOutcome attemptOutcome, DlqReason dlqReason,
            String errorCode, String errorMessage, Integer httpStatus, String snippet
    ) implements PersistableOutcome {
    }

    /** Provider-requested deferral: no attempt row, the attempt consumed by the claim is refunded. */
    record Defer(Instant startedAt, Instant finishedAt, String errorCode, Instant nextRetryAt) implements PersistableOutcome {
    }
}
