package com.notify.delivery.provider.webhook;

import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.core5.function.Resolver;

/**
 * Supplies the connection timeouts of the attempt that is currently running on this thread (, F7).
 *
 * <p>Apache HttpClient resolves {@link ConnectionConfig} per route, not per request, so a single configured value
 * would apply to every attempt regardless of how much of its budget is left. The sender publishes the config derived
 * from the live {@link WebhookDeadline} here for the duration of one request — the same thread-bound technique the
 * {@link PinnedDnsResolver} already uses, and safe for the same reason: the classic client executes the exchange on
 * the calling thread.
 *
 * <p>Fail-safe rather than fail-closed: with nothing published, the configured default applies, so a connection
 * obtained outside a webhook attempt still has bounded timeouts.
 *
 * <p>Not component-scanned: it needs the configured fallback, so {@link WebhookHttpClientConfig} builds it.
 */
public class WebhookConnectionBudget implements Resolver<HttpRoute, ConnectionConfig> {

    private static final ThreadLocal<ConnectionConfig> CURRENT = new ThreadLocal<>();

    private final ConnectionConfig fallback;

    public WebhookConnectionBudget(ConnectionConfig fallback) {
        this.fallback = fallback;
    }

    @Override
    public ConnectionConfig resolve(HttpRoute route) {
        ConnectionConfig current = CURRENT.get();
        return current == null ? fallback : current;
    }

    /** Runs {@code action} with {@code config} applied to any connection this thread opens. */
    <T> T withConfig(ConnectionConfig config, PinnedDnsResolver.PinnedCall<T> action) throws Exception {
        ConnectionConfig previous = CURRENT.get();
        CURRENT.set(config);
        try {
            return action.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
