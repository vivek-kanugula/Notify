package com.notify.dlq.service;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * An opaque keyset cursor over {@code (created_at, id)} (/ , ).
 *
 * <p>Keyset rather than offset, matching the existing overdue and stuck-work finders, so a page cannot drift as
 * rows are added. A malformed cursor is {@code 422 VALIDATION_FAILED} - the precedent - and introduces no
 * new error code.
 */
public record DlqCursor(Instant createdAt, UUID id) {

    public static DlqCursor of(Instant createdAt, UUID id) {
        return new DlqCursor(createdAt, id);
    }

    public String encode() {
        String raw = createdAt.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns null for a blank cursor (the first page) and throws for a malformed one. */
    public static DlqCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            if (separator < 0) {
                throw new IllegalArgumentException("missing separator");
            }
            return new DlqCursor(
                    Instant.parse(raw.substring(0, separator)),
                    UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, "cursor is not a valid pagination cursor");
        }
    }
}
