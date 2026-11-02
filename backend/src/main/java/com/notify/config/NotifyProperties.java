package com.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly typed configuration properties for Notify platform.
 */
@ConfigurationProperties(prefix = "notify")
public record NotifyProperties(
    String role,
    String publicUrl,
    String frontendUrl,
    DemoProperties demo
) {
    public NotifyProperties {
        if (role == null || role.isBlank()) {
            role = "all";
        }
        if (publicUrl == null || publicUrl.isBlank()) {
            publicUrl = "http://localhost:8080";
        }
        if (frontendUrl == null || frontendUrl.isBlank()) {
            frontendUrl = "http://localhost:5173";
        }
        if (demo == null) {
            demo = new DemoProperties(false);
        }
    }

    public record DemoProperties(
        boolean publicAccess
    ) {
        public DemoProperties {
            // default to false for secure boundary
        }
    }
}
