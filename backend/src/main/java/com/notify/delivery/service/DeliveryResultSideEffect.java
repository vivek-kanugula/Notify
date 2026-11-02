package com.notify.delivery.service;

import com.notify.common.model.Channel;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.notification.dto.ClaimSnapshot;

/**
 * A channel-scoped durable write that must commit <b>with</b> the delivery result (/ , ).
 *
 * <p>It exists for IN_APP, whose delivery <em>is</em> a database write. Running that write in the adapter would put
 * it outside the checked result transaction, so a fenced-out or rolled-back claim could leave a visible inbox row for
 * a notification that never reached DELIVERED. Running it here, inside
 * {@link DeliveryResultService#persist(ClaimSnapshot, PersistableOutcome)} and <em>after</em> the fenced status
 * update, makes the row and the status atomic in both directions.
 *
 * <p><b>Mandatory (condition C1), in both directions.</b> An implementation that cannot complete its write must
 * throw: the result transaction then rolls back and the notification is never recorded DELIVERED, so a missing in-app
 * row can never be reported as a successful delivery. And for a channel whose delivery <em>is</em> this write, an
 * implementation being <b>absent</b> fails the same way - {@code DeliveryResultService} refuses to complete the
 * result rather than skipping a lookup that came back {@code null}. Registering an implementation for IN_APP is
 * therefore a deployment requirement, not an optional extension point.
 *
 * <p>Called only for {@link PersistableOutcome.Delivered}. EMAIL and WEBHOOK register no side effect, so their
 * behaviour is byte-for-byte unchanged.
 */
public interface DeliveryResultSideEffect {

    Channel channel();

    /** Runs inside the result transaction, after the fence. Must throw rather than skip its write. */
    void afterFencedDelivery(ClaimSnapshot claim, PersistableOutcome.Delivered outcome);
}