package com.notify.dlq.repository;

import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.common.model.DlqStatus;
import com.notify.dlq.entity.DeadLetterEntryEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Keyset search over dead-letter entries (/ , ).
 *
 * <p>A custom fragment rather than a {@code @Query} with {@code :param is null} tests: PostgreSQL cannot infer the
 * type of a parameter that only ever appears inside {@code ? is null}, so such a query fails with
 * "could not determine data type of parameter". The Criteria implementation adds a predicate only when the filter
 * is actually present, which is both typed and cheaper to plan.
 */
public interface DeadLetterEntrySearch {

    default List<DeadLetterEntryEntity> searchEntries(
            DlqStatus status,
            Channel channel,
            DlqReason reason,
            UUID tenantId,
            Instant from,
            Instant to,
            Instant cursorAt,
            UUID cursorId,
            int limit
    ) {
        return searchEntries(status, channel, reason, tenantId, null, from, to, cursorAt, cursorId, limit);
    }

    List<DeadLetterEntryEntity> searchEntries(
            DlqStatus status,
            Channel channel,
            DlqReason reason,
            UUID tenantId,
            String tenantName,
            Instant from,
            Instant to,
            Instant cursorAt,
            UUID cursorId,
            int limit
    );
}
