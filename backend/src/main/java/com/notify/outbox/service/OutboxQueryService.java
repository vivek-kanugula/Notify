package com.notify.outbox.service;

import java.time.Instant;
import java.util.Optional;

/**
 * Read-only boundary of the outbox feature for metrics gauges (/ ). The observability feature depends
 * on this interface, never on {@code OutboxEventRepository}.
 */
public interface OutboxQueryService {

    long pendingCount();

    Optional<Instant> oldestPendingCreatedAt();
}
