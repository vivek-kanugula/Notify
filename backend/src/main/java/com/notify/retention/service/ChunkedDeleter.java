package com.notify.retention.service;

import com.notify.retention.config.RetentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs a retention {@code DELETE} in bounded chunks (/ -3).
 *
 * <p>Each chunk is its own statement, so a table that is months overdue is cleared over many small
 * transactions instead of one that would hold locks across the whole backlog. A sweep stops as soon as a
 * chunk deletes fewer rows than the batch size, and in any case after {@code maxIterationsPerSweep}, so one
 * invocation can never run unbounded.
 *
 * <p>Retention deliberately uses SQL through {@link JdbcTemplate} rather than other features' repositories:
 * it is table housekeeping, not domain behaviour, and this keeps the retention feature from injecting
 * repositories it does not own.
 */
@Component
public class ChunkedDeleter {

    private static final Logger log = LoggerFactory.getLogger(ChunkedDeleter.class);

    private final JdbcTemplate jdbc;
    private final RetentionProperties properties;

    public ChunkedDeleter(JdbcTemplate jdbc, RetentionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public int batchSize() {
        return properties.batchSize();
    }

    /**
     * @param label human-readable sweep name for the log line
     * @param sql   a delete statement whose inner select is already {@code LIMIT}ed to one chunk
     * @param args  bind parameters for {@code sql}
     * @return total rows deleted across all chunks
     */
    public int sweep(String label, String sql, Object... args) {
        int total = 0;
        for (int iteration = 0; iteration < properties.maxIterationsPerSweep(); iteration++) {
            int deleted = jdbc.update(sql, args);
            total += deleted;
            if (deleted < properties.batchSize()) {
                break;
            }
        }
        if (total > 0) {
            log.info("Retention sweep {}: deleted {} rows", label, total);
        } else {
            log.debug("Retention sweep {}: nothing to delete", label);
        }
        return total;
    }
}
