package com.notify.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Demo webhook receiver settings ({@code notify.demo.receiver.*}, ).
 *
 * <p>{@code chaosEnabled} gates the failure-injection routes. They are a verification affordance, not a product
 * feature: a route that can be told to answer 500 or to hang must not exist in production, so
 * {@code WebhookStartupGuard} fails the context when the {@code prod} profile has them switched on.
 */
@ConfigurationProperties(prefix = "notify.demo.receiver")
public record DemoWebhookProperties(@DefaultValue("false") boolean chaosEnabled) {
}
