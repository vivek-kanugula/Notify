package com.notify.inapp.dto;

import com.notify.inapp.model.InAppMessageView;

import java.time.Instant;
import java.util.UUID;

/**
 * One inbox message as the API returns it (/ , ).
 *
 * <p>{@code seq} is serialised as a <b>decimal string</b>: it is a 64-bit cursor, and a JSON number would be
 * rounded by a browser beyond 2^53. Neither {@code tenantId} nor {@code recipientRef} appears — both are inputs to
 * the query, never outputs.
 */
public record InAppMessageResponse(
        String seq,
        UUID notificationId,
        String subject,
        String body,
        boolean read,
        Instant readAt,
        Instant createdAt
) {

    public static InAppMessageResponse from(InAppMessageView view) {
        return new InAppMessageResponse(
                Long.toString(view.seq()),
                view.notificationId(),
                view.subject(),
                view.body(),
                view.readAt() != null,
                view.readAt(),
                view.createdAt());
    }
}
