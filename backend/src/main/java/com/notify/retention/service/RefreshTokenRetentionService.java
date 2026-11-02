package com.notify.retention.service;

import com.notify.retention.config.RetentionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;

/**
 * Deletes spent refresh tokens 30 days past their expiry or revocation (schema §Retention).
 *
 * <p>Only tokens that are already useless are touched: an expired token cannot be exchanged, and a revoked
 * one is refused. Live tokens have {@code revoked_at IS NULL} and a future {@code expires_at}, so they can
 * never match.
 */
@Service
@ConditionalOnProperty(prefix = "notify.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RefreshTokenRetentionService {

    private final ChunkedDeleter deleter;
    private final RetentionProperties properties;
    private final Clock clock;

    public RefreshTokenRetentionService(ChunkedDeleter deleter, RetentionProperties properties, Clock clock) {
        this.deleter = deleter;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${notify.retention.refresh-tokens-cron:0 15 3 * * *}")
    public void scheduledSweep() {
        sweep();
    }

    /** @return rows deleted */
    public int sweep() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(properties.refreshTokens()));
        String sql = """
                DELETE FROM refresh_tokens WHERE id IN (
                    SELECT id FROM refresh_tokens
                    WHERE expires_at < ? OR (revoked_at IS NOT NULL AND revoked_at < ?)
                    LIMIT %d
                )
                """.formatted(deleter.batchSize());
        return deleter.sweep("refresh_tokens", sql, cutoff, cutoff);
    }
}
