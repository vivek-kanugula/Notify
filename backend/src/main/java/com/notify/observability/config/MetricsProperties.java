package com.notify.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Metrics settings ({@code notify.metrics.*}) for / .
 *
 * <p>{@code tenantTag} (bound from {@code notify.metrics.tenant-tag}, default {@code false}) gates the
 * {@code tenant_id} label on custom meters (). When {@code false}, meters aggregate globally so label
 * cardinality stays bounded; {@code true} is for local dev / low-tenant environments only.
 */
@ConfigurationProperties(prefix = "notify.metrics")
public record MetricsProperties(@DefaultValue("false") boolean tenantTag) {
}
