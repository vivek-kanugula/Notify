package com.notify.inapp.service;

import com.notify.inapp.model.InAppMessageView;

import java.util.List;
import java.util.UUID;

/**
 * The in-app feature's boundary (/ ). Other features - the SSE stream, the read API - depend on this interface
 * and never on the repository or the entity, per the project's feature-boundary rule.
 */
public interface InAppMessageService {

    /**
     * The drain and catch-up read (): messages for this recipient with {@code seq > cursor}, ascending, at most
     * {@code limit + 1} rows. The extra row is the {@code resync} probe, so the caller decides on eligible rows.
     */
    List<InAppMessageView> ascendingAbove(UUID tenantId, String recipientRef, long cursor, int limit);

    /** Newest-first keyset page for the inbox; {@code before} of {@code null} means "from the newest". */
    List<InAppMessageView> page(UUID tenantId, String recipientRef, Long before, int size);

    long countUnread(UUID tenantId, String recipientRef);

    /** @return false when the message does not exist for this tenant and recipient, so the caller answers 404. */
    boolean markRead(UUID tenantId, String recipientRef, long seq);
}