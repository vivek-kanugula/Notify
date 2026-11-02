package com.notify.inapp.service;

import com.notify.inapp.model.InAppMessageView;
import com.notify.inapp.repository.InAppMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Tenant-and-recipient scoped reads over {@code inapp_messages} (/ ). */
@Service
public class DefaultInAppMessageService implements InAppMessageService {

    private final InAppMessageRepository repository;
    private final Clock clock;

    public DefaultInAppMessageService(InAppMessageRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<InAppMessageView> ascendingAbove(UUID tenantId, String recipientRef, long cursor, int limit) {
        return map(repository.findAscendingAbove(tenantId, recipientRef, cursor, limit));
    }

    @Override
    @Transactional(readOnly = true)
    public List<InAppMessageView> page(UUID tenantId, String recipientRef, Long before, int size) {
        long cursor = before == null ? Long.MAX_VALUE : before;
        return map(repository.findDescendingBelow(tenantId, recipientRef, cursor, size));
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnread(UUID tenantId, String recipientRef) {
        return repository.countUnread(tenantId, recipientRef);
    }

    @Override
    @Transactional
    public boolean markRead(UUID tenantId, String recipientRef, long seq) {
        if (repository.existsForRecipient(tenantId, recipientRef, seq) == 0) {
            return false;                                  // not this tenant's, or not this recipient's: 404
        }
        repository.markRead(tenantId, recipientRef, seq, clock.instant());
        return true;                                       // already-read rows update 0 rows and stay successful
    }

    private static List<InAppMessageView> map(List<Object[]> rows) {
        List<InAppMessageView> views = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            views.add(new InAppMessageView(
                    ((Number) row[0]).longValue(),
                    (UUID) row[1],
                    (String) row[2],
                    (String) row[3],
                    instant(row[4]),
                    instant(row[5])));
        }
        return views;
    }

    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offset) {
            return offset.toInstant();
        }
        return (Instant) value;
    }
}