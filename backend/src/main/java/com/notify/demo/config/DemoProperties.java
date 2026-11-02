package com.notify.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;

import java.util.List;

/**
 * Demo surface settings ({@code notify.demo.*}).
 *
 * <p>{@code webhookUrl} is the destination a new tenant's default endpoint points at (); it is set in
 * {@code application-local.yml} only, so the production and test signup paths provision nothing.
 *
 * <p>{@code enabled} (bound from {@code notify.demo.public}, default {@code false}) and {@code tenantSlugs}
 * gate the public demo inbox (/ , ). With no {@code tenants.is_demo} column and no new
 * migrations permitted (H1), demo tenants are identified by this configured slug list.
 */
@ConfigurationProperties(prefix = "notify.demo")
public record DemoProperties(
        @DefaultValue("") String webhookUrl,
        @Name("public") @DefaultValue("false") boolean enabled,
        @DefaultValue("") List<String> tenantSlugs) {

    @ConstructorBinding
    public DemoProperties {
        webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        tenantSlugs = tenantSlugs == null ? List.of()
                : tenantSlugs.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Back-compat for callers predating the demo-inbox fields (/ ). */
    public DemoProperties(String webhookUrl) {
        this(webhookUrl, false, List.of());
    }

    public boolean provisioningEnabled() {
        return !webhookUrl.isEmpty();
    }

    /** The public demo inbox is served only when explicitly enabled and at least one demo tenant is configured. */
    public boolean inboxEnabled() {
        return enabled && !tenantSlugs.isEmpty();
    }
}
