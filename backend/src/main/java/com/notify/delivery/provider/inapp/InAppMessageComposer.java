package com.notify.delivery.provider.inapp;

import com.notify.notification.dto.ClaimSnapshot;
import org.springframework.stereotype.Component;

/**
 * Turns a claimed notification into the subject and body that will be stored in the inbox (/ ).
 *
 * <p>Pure by construction: no I/O, no database, no clock. The column widths are V1's and are enforced here rather
 * than by a database error, because a truncation must never be the thing that rolls back a delivery.
 */
@Component
public class InAppMessageComposer {

    /** {@code inapp_messages.subject} is VARCHAR(512) in V1. */
    public static final int SUBJECT_LIMIT = 512;

    public Composed compose(ClaimSnapshot claim) {
        return new Composed(limit(claim.subject(), SUBJECT_LIMIT), claim.body() == null ? "" : claim.body());
    }

    /** Subject may be null; body is never null, because the column is NOT NULL. */
    public record Composed(String subject, String body) {
    }

    private static String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}