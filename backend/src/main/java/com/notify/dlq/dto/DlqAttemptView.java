package com.notify.dlq.dto;

import java.time.Instant;

/**
 * One historical delivery attempt, as the admin detail shows it ().
 *
 * <p>Carries the code, the status and the timing - and deliberately **not** {@code error_message} or
 * {@code response_snippet}, which can contain a receiver body excerpt ().
 */
public record DlqAttemptView(
        int attemptNumber,
        int deliveryCycle,
        String outcome,
        String errorCode,
        Integer httpStatus,
        String provider,
        Long latencyMs,
        Instant startedAt,
        Instant finishedAt
) {
}
