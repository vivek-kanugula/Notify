package com.notify.idempotency.model;

/**
 * Result of in-flight lock acquisition.
 * Either the lock was acquired (carrying the lockToken), or a verified DONE entry
 * was found during lock acquisition, allowing a matching replay to be returned without
 * deducting tenant rate-limit quota ().
 */
public sealed interface LockResult permits LockResult.Acquired, LockResult.Replay {

    record Acquired(String token) implements LockResult {}

    record Replay(ReplayResult result) implements LockResult {}

    static LockResult acquired(String token) {
        return new Acquired(token);
    }

    static LockResult replay(ReplayResult result) {
        return new Replay(result);
    }
}
