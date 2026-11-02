package com.notify.delivery.provider.webhook;

import com.notify.webhook.config.WebhookProperties;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The HTTP client used for webhook delivery (/ , Option A). Apache HttpClient 5 is here for one reason: it is
 * the only option on this stack that lets us supply the name resolution, so the connection goes to an address the
 * destination policy already validated ({@link PinnedDnsResolver}). The Resend adapter keeps its JDK client.
 *
 * <p>Deliberate settings, each one a policy decision rather than a tuning choice:
 * <ul>
 *   <li>redirects disabled — a 3xx is a permanent failure, never a second request to an unvalidated location;</li>
 *   <li>automatic retries disabled — the worker owns every retry decision, so the attempt count stays truthful;</li>
 *   <li>connections never reused and expire after one second — a pooled connection must not outlive the DNS answer
 *       that was validated for it;</li>
 *   <li>cookies and auth caching disabled — nothing about one tenant's receiver may leak into another request;</li>
 *   <li>protocol upgrade disabled — one predictable HTTP/1.1 exchange per attempt.</li>
 * </ul>
 *
 * <p>The timeouts configured here are only the fallback for a connection obtained outside an attempt. A real attempt
 * derives every phase timeout from its own remaining budget: {@link WebhookConnectionBudget} supplies the connection
 * timeouts and the sender supplies the request timeouts (, F7).
 */
@Configuration
public class WebhookHttpClientConfig {

    public static final String CLIENT_BEAN = "webhookHttpClient";

    /** Request policy shared by every attempt; only the two timeouts are rebuilt per attempt. */
    static RequestConfig baseRequestConfig() {
        return RequestConfig.custom()
                .setRedirectsEnabled(false)
                .setProtocolUpgradeEnabled(false)
                .setExpectContinueEnabled(false)
                .build();
    }

    /**
     * The production name resolver (F7). Four threads and eight queue slots: enough for the webhook worker's three
     * listener threads plus the configuration API's test ping, and small enough that a black-holed resolver costs a
     * fixed, known amount. Saturation is reported as a resolution failure, which the delivery path retries.
     */
    @Bean(destroyMethod = "destroy")
    public com.notify.webhook.security.BoundedDnsResolver webhookDnsResolver() {
        return new com.notify.webhook.security.BoundedDnsResolver(4, 8);
    }

    @Bean
    public WebhookConnectionBudget webhookConnectionBudget(WebhookProperties properties) {
        Duration connect = properties.connectTimeout();
        Duration total = properties.totalTimeout();
        return new WebhookConnectionBudget(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connect.toMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(total.toMillis()))
                .setTimeToLive(TimeValue.ofSeconds(1))
                .build());
    }

    @Bean(name = CLIENT_BEAN, destroyMethod = "close")
    public CloseableHttpClient webhookHttpClient(
            WebhookProperties properties,
            PinnedDnsResolver dnsResolver,
            WebhookConnectionBudget connectionBudget
    ) {
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dnsResolver)
                // Per-attempt timeouts: resolved from the live budget rather than from one static value.
                .setConnectionConfigResolver(connectionBudget)
                .setMaxConnTotal(16)
                .setMaxConnPerRoute(4)
                .build();

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.copy(baseRequestConfig())
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(properties.connectTimeout().toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(properties.totalTimeout().toMillis()))
                        .build())
                .setUserAgent(properties.userAgent())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .setConnectionReuseStrategy((request, response, context) -> false)
                .build();
    }
}
