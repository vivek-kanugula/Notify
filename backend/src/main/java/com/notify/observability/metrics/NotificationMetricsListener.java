package com.notify.observability.metrics;

import com.notify.notification.event.NotificationTransitionedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Records the {@code notify.notifications.transition} counter from committed lifecycle transitions (/ ).
 *
 * <p>Fully decoupled from the transition engine: it listens for {@link NotificationTransitionedEvent} after
 * commit, so a rolled-back transition is never counted and no core path changes. Telemetry failures here never
 * affect delivery (the listener only increments an in-memory counter).
 */
@Component
public class NotificationMetricsListener {

    private final NotifyMetrics metrics;

    public NotificationMetricsListener(NotifyMetrics metrics) {
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransition(NotificationTransitionedEvent event) {
        metrics.recordTransition(event.fromStatus(), event.toStatus(), event.actor());
    }
}
