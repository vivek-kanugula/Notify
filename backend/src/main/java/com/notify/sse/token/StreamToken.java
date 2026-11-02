package com.notify.sse.token;

import java.util.UUID;

/** The verified binding a consumed stream token yields (/ ): one tenant, one recipient. */
public record StreamToken(UUID tenantId, String recipientRef) {
}
