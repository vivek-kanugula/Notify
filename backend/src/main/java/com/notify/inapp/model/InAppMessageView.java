package com.notify.inapp.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One inbox message as every caller sees it (/ ). Carries no {@code tenant_id} and no {@code recipient_ref}:
 * both are inputs to the query, never outputs, so a projection can never widen the isolation boundary.
 *
 * <p>{@code seq} is the cursor the accepted SSE contract calls {@code lastEventId}.
 */
public record InAppMessageView(
        long seq,
        UUID notificationId,
        String subject,
        String body,
        Instant readAt,
        Instant createdAt
) {
}