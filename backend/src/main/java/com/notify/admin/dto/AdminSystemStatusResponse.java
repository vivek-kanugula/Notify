package com.notify.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Live component health for the admin UI (). {@code status} is the overall roll-up (UP when the
 * authoritative database is UP). {@code grafanaLinks} is a placeholder in and populated in (L1).
 */
public record AdminSystemStatusResponse(
        String status,
        Instant timestamp,
        Map<String, ComponentHealth> components,
        List<BreakerStatus> circuitBreakers,
        Map<String, String> grafanaLinks
) {
    public record ComponentHealth(String status, Map<String, Object> details) {}

    public record BreakerStatus(String name, String state, double failureRate) {}
}
