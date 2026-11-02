package com.notify.common.model;

import java.util.Set;

public enum NotificationStatus {
    CREATED,
    SCHEDULED,
    QUEUED,
    PROCESSING,
    RETRYING,
    DELIVERED,
    FAILED,
    DLQ,
    CANCELLED;

    private static final Set<NotificationStatus> TERMINAL_STATUSES = Set.of(
            DELIVERED,
            FAILED,
            DLQ,
            CANCELLED
    );

    public boolean isTerminal() {
        return TERMINAL_STATUSES.contains(this);
    }
}
