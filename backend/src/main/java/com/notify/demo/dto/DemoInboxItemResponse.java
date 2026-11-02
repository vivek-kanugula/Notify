package com.notify.demo.dto;

import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Public demo inbox projection (/ L4): masked recipient and sanitized subject only.
 * Bodies, headers, template content and metadata are never exposed.
 */
public record DemoInboxItemResponse(
        UUID id,
        String tenantSlug,
        String tenantName,
        Channel channel,
        NotificationStatus status,
        String recipient,
        String subject,
        Instant createdAt
) {
    /** Backwards-compatible constructor for callers omitting tenant details. */
    public DemoInboxItemResponse(UUID id, Channel channel, NotificationStatus status,
                                 String recipient, String subject, Instant createdAt) {
        this(id, null, null, channel, status, recipient, subject, createdAt);
    }
}
