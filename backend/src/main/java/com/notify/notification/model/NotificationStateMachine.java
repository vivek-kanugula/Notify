package com.notify.notification.model;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.Actor;
import com.notify.common.model.NotificationStatus;
import org.springframework.http.HttpStatus;
import java.util.Map;
import java.util.Set;

/**
 * Pure actor-aware notification state machine.
 * Enforces the legal transitions defined in and CONTRACTS.md.
 */
public final class NotificationStateMachine {

    private record TransitionKey(NotificationStatus from, NotificationStatus to) {}

    private static final Map<TransitionKey, Set<Actor>> LEGAL_TRANSITIONS = new java.util.HashMap<>();

    static {
        // Initial creation transitions (SYSTEM only)
        allow(NotificationStatus.CREATED, NotificationStatus.SCHEDULED, Actor.SYSTEM);
        allow(NotificationStatus.CREATED, NotificationStatus.QUEUED, Actor.SYSTEM);

        // Scheduled due transitions
        allow(NotificationStatus.SCHEDULED, NotificationStatus.QUEUED, Actor.SYSTEM);

        // Cancellations
        allow(NotificationStatus.SCHEDULED, NotificationStatus.CANCELLED, Actor.TENANT, Actor.SYSTEM);
        allow(NotificationStatus.QUEUED, NotificationStatus.CANCELLED, Actor.TENANT, Actor.SYSTEM);
        // RETRYING can ONLY be cancelled by SYSTEM as side-effect of admin tenant revocation
        allow(NotificationStatus.RETRYING, NotificationStatus.CANCELLED, Actor.SYSTEM);

        // Retry due transitions (/ )
        allow(NotificationStatus.RETRYING, NotificationStatus.QUEUED, Actor.SYSTEM);

        // Worker claim
        allow(NotificationStatus.QUEUED, NotificationStatus.PROCESSING, Actor.SYSTEM);
        allow(NotificationStatus.RETRYING, NotificationStatus.PROCESSING, Actor.SYSTEM);

        // Worker outcomes
        allow(NotificationStatus.PROCESSING, NotificationStatus.DELIVERED, Actor.SYSTEM);
        allow(NotificationStatus.PROCESSING, NotificationStatus.RETRYING, Actor.SYSTEM);
        allow(NotificationStatus.PROCESSING, NotificationStatus.FAILED, Actor.SYSTEM);

        // Dead letter materialization
        allow(NotificationStatus.FAILED, NotificationStatus.DLQ, Actor.SYSTEM);

        // Admin replay
        allow(NotificationStatus.DLQ, NotificationStatus.QUEUED, Actor.ADMIN);
    }

    private static void allow(NotificationStatus from, NotificationStatus to, Actor... actors) {
        LEGAL_TRANSITIONS.put(new TransitionKey(from, to), Set.of(actors));
    }

    private NotificationStateMachine() {
    }

    public static boolean isValidTransition(NotificationStatus from, NotificationStatus to, Actor actor) {
        if (from == null || to == null || actor == null) {
            return false;
        }
        Set<Actor> allowedActors = LEGAL_TRANSITIONS.get(new TransitionKey(from, to));
        return allowedActors != null && allowedActors.contains(actor);
    }

    public static void validateTransition(NotificationStatus from, NotificationStatus to, Actor actor) {
        if (!isValidTransition(from, to, actor)) {
            throw new NotifyException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    HttpStatus.CONFLICT,
                    String.format("Illegal state transition from %s to %s by actor %s", from, to, actor)
            );
        }
    }
}
