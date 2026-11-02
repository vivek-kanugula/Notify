package com.notify.inapp.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * The advisory-lock key for one {@code (tenantId, recipientRef)} pair (/ , ).
 *
 * <p>Computed in Java from a SHA-256 digest rather than with PostgreSQL's {@code hashtext}, so the value does not
 * depend on the database's collation, locale or version - a lock key that silently changed across an upgrade would
 * silently weaken the ordering guarantee.
 *
 * <p>A collision only makes two unrelated recipients share one lock, which costs a little serialisation and nothing
 * in correctness.
 */
public final class InAppRecipientLockKey {

    private InAppRecipientLockKey() {
    }

    public static long of(UUID tenantId, String recipientRef) {
        byte[] digest = sha256("inapp:" + tenantId + ":" + recipientRef);
        long key = 0L;
        for (int i = 0; i < 8; i++) {
            key = (key << 8) | (digest[i] & 0xFFL);
        }
        return key;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every supported JVM", e);
        }
    }
}