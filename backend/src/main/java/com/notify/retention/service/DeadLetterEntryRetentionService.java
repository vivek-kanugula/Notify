package com.notify.retention.service;

import com.notify.retention.config.RetentionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;

/**
 * Deletes resolved dead-letter entries 180 days after resolution (schema §Retention).
 *
 * <p><b>{@code OPEN} entries are never deleted</b>, whatever their age: an open entry is unfinished operator
 * work, and the schema keeps it until it is explicitly replayed or discarded.
 *
 * <p>The resolved set is {@code REPLAYED} and {@code DISCARDED}. {@code DlqStatus} has exactly three values —
 * {@code OPEN}, {@code REPLAYED}, {@code DISCARDED} — and the table's CHECK constraint matches; there is no
 * {@code RESOLVED} status, despite that wording appearing in the plan.
 */
@Service
@ConditionalOnProperty(prefix = "notify.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DeadLetterEntryRetentionService {

    private final ChunkedDeleter deleter;
    private final RetentionProperties properties;
    private final Clock clock;

    public DeadLetterEntryRetentionService(ChunkedDeleter deleter, RetentionProperties properties, Clock clock) {
        this.deleter = deleter;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${notify.retention.dead-letter-entries-cron:0 45 3 * * *}")
    public void scheduledSweep() {
        sweep();
    }

    /** @return rows deleted */
    public int sweep() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(properties.deadLetterEntries()));
        String sql = """
                DELETE FROM dead_letter_entries WHERE id IN (
                    SELECT id FROM dead_letter_entries
                    WHERE status IN ('REPLAYED', 'DISCARDED')
                      AND resolved_at IS NOT NULL
                      AND resolved_at < ?
                    LIMIT %d
                )
                """.formatted(deleter.batchSize());
        return deleter.sweep("dead_letter_entries", sql, cutoff);
    }
}
