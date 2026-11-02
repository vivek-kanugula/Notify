package com.notify.delivery.worker.model;

import java.util.UUID;

/**
 * What every channel's dispatch event must carry for the shared worker flow: identifiers and the generation fence.
 * PostgreSQL stays authoritative for everything else, so no envelope carries bodies, PII or mutable budgets.
 */
public interface DispatchEnvelope {

    UUID notificationId();

    UUID tenantId();

    Integer generation();
}
