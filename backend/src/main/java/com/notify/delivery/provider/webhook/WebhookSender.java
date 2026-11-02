package com.notify.delivery.provider.webhook;

import com.notify.delivery.model.DeliveryResult;
import com.notify.webhook.config.WebhookProperties;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sends one prepared webhook request (, ). Transport only: it decides nothing about retries and never
 * touches the database.
 *
 * <p>The request goes out while its host is pinned to the addresses the destination policy cleared, so the TCP
 * connection cannot reach an address that was never validated, while TLS still uses the configured host name for SNI
 * and certificate verification.
 *
 * <p><b>One budget for the whole attempt (F7).</b> The caller opens a {@link WebhookDeadline} before name resolution
 * and hands it here. Every phase timeout — pool lease, connect per resolved address, socket, response headers, body
 * read — is derived from what the budget still has, not from its own configured value, so the worst case is the budget
 * instead of the sum of the phases. A budget already spent by resolution fails as a timeout without opening a socket.
 *
 * <p>A known 2xx is never turned into a failure by its body: an oversize, slow or unreadable body is cut off and
 * reported as truncated, which is what requires.
 */
@Component
public class WebhookSender {

    /** Appended inside the stored budget so a cut-off snippet can never be read as a whole response. */
    static final String TRUNCATION_MARKER = "...[truncated]";
    private static final int MARKER_BYTES = TRUNCATION_MARKER.getBytes(StandardCharsets.UTF_8).length;
    /** Longest Retry-After value kept; a receiver cannot use this field to write an essay into our attempt row. */
    private static final int MAX_RETRY_AFTER_CHARS = 32;

    /**
     * One shared daemon timer for every attempt's deadline abort. A single thread is enough because the task only
     * calls {@code abort()}, and one thread is a fixed cost no matter how many attempts run.
     */
    private static final ScheduledExecutorService WATCHDOG = watchdogExecutor();

    private static ScheduledExecutorService watchdogExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "webhook-deadline-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        // Without this, a cancelled task stays in the delay queue until its delay elapses, so the queue would hold
        // one entry per completed attempt for up to a whole budget. Removing on cancel keeps it proportional to the
        // attempts actually in flight.
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private final CloseableHttpClient client;
    private final PinnedDnsResolver dnsResolver;
    private final WebhookConnectionBudget connectionBudget;
    private final WebhookProperties properties;

    /** The resilience seam (/ ). Null in the legacy constructor, which means "no guard": call directly. */
    private final com.notify.delivery.resilience.ProviderCallGuard guard;
    private final com.notify.delivery.resilience.HostBreakerRegistry hostBreakers;
    private final io.github.resilience4j.bulkhead.Bulkhead bulkhead;

    @org.springframework.beans.factory.annotation.Autowired
    public WebhookSender(
            @Qualifier(WebhookHttpClientConfig.CLIENT_BEAN) CloseableHttpClient client,
            PinnedDnsResolver dnsResolver,
            WebhookConnectionBudget connectionBudget,
            WebhookProperties properties,
            com.notify.delivery.resilience.ProviderCallGuard guard,
            com.notify.delivery.resilience.HostBreakerRegistry hostBreakers,
            @Qualifier("webhookBulkhead") io.github.resilience4j.bulkhead.Bulkhead bulkhead
    ) {
        this.client = client;
        this.dnsResolver = dnsResolver;
        this.connectionBudget = connectionBudget;
        this.properties = properties;
        this.guard = guard;
        this.hostBreakers = hostBreakers;
        this.bulkhead = bulkhead;
    }

    /** Without the resilience seam, for tests that exercise the transport in isolation. */
    public WebhookSender(
            CloseableHttpClient client,
            PinnedDnsResolver dnsResolver,
            WebhookConnectionBudget connectionBudget,
            WebhookProperties properties
    ) {
        this(client, dnsResolver, connectionBudget, properties, null, null, null);
    }

    /** Sends with a fresh budget. Used by paths that have done no work before the send. */
    public WebhookSendOutcome send(WebhookSendRequest request) {
        return send(request, WebhookDeadline.startingNow(properties.totalTimeout()));
    }

    /**
     * Sends under the resilience guard when one is wired (/ , ).
     *
     * <p>The breaker is keyed by the destination's <b>host</b>, from the bounded registry, so a failing host is
     * isolated and two tenants on the same host share its state - the coupling accepts. A refusal is raised as
     * {@code ProviderDeferredException} <b>before any socket is opened</b>, which is what makes the deferral
     * attempt-free; the adapter turns it into {@code DeliveryResult.Deferred}.
     *
     * <p>Exactly what {@link WebhookResponseMapper} classifies retryable counts against the breaker, and nothing
     * else: a tenant's own misconfigured receiver - a permanent 4xx, a redirect, an unparsable response - must not
     * open a breaker that defers another tenant's deliveries to the same host.
     */
    public WebhookSendOutcome send(WebhookSendRequest request, WebhookDeadline deadline) {
        if (guard == null) {
            return sendDirectly(request, deadline);
        }
        String host = request.destination().url().host();
        return guard.execute(
                "webhook:" + host,
                hostBreakers.forHost(host),
                bulkhead,
                null,                            // no outbound rate limit for tenant-owned receivers ()
                WebhookSender::countsAgainstTheBreaker,
                () -> sendDirectly(request, deadline));
    }

    /**
     * Whether an outcome counts as a provider failure for the breaker.
     *
     * <p><b>Asks the mapper rather than restating its rules.</b> The earlier version kept its own list - any
     * transport failure, a {@code 429} or a {@code 5xx} - and that list diverged from
     * {@link WebhookResponseMapper} in both directions. A {@code 408} is retryable there but was invisible here, so
     * a receiver answering {@code 408} for ever never opened a breaker. {@code PROTOCOL} is <b>permanent</b> there
     * but counted here, so one tenant's receiver sending malformed responses could open the breaker for the
     * <em>host</em> and defer other tenants' deliveries to it - exactly the coupling the paragraph above says must
     * not happen. Deriving the answer from the mapper's own result removes the second rule entirely: whatever the
     * mapper calls retryable counts, and everything it calls permanent or successful does not.
     *
     * <p>The mapping is pure and allocation-light, and it runs once per attempt, so calling it here costs nothing
     * worth trading accuracy for.
     *
     * <p>Package-private rather than private for one reason: two of the outcomes it must classify correctly -
     * {@code PROTOCOL} and {@code BLOCKED} - cannot be provoked through a real socket from this client (a bad
     * status line arrives as a plain {@code IOException}, a malformed body is tolerated by the bounded reader, and
     * {@code BLOCKED} is decided by the resolver before the sender is ever called). The guarded path is exercised
     * end to end with the outcomes a receiver can actually produce; these two are pinned here against the mapper.
     */
    static boolean countsAgainstTheBreaker(WebhookSendOutcome outcome) {
        return WebhookResponseMapper.map(outcome) instanceof DeliveryResult.RetryableFailure;
    }

    private WebhookSendOutcome sendDirectly(WebhookSendRequest request, WebhookDeadline deadline) {
        if (deadline.expired()) {
            // Resolution alone used the budget: no socket is opened, and the attempt is a timeout as it would be
            // if the receiver had not answered.
            return WebhookSendOutcome.failure(WebhookSendOutcome.Failure.TIMEOUT,
                    "The attempt budget was spent before the request could be sent", deadline.elapsedMs());
        }

        // toASCIIString() percent-encodes any non-ASCII the tenant left in the path or query; the host is already
        // punycode and canonical by the time the policy accepts it.
        URI target = URI.create(request.destination().url().uri().toASCIIString());

        HttpPost post = new HttpPost(target);
        request.headers().forEach(post::setHeader);
        post.setEntity(new ByteArrayEntity(request.body(), ContentType.APPLICATION_JSON));

        int addressCount = request.destination().addresses().size();
        long connectMs = deadline.connectTimeoutMs(properties.connectTimeout().toMillis(), addressCount);
        // Every timeout goes through the deadline's floor. A zero timeout means "no timeout" to HttpClient
        // (TimeValue.ZERO_MILLISECONDS is the disabled value), so an expiring budget must never produce one —
        // that would turn expiry into an unlimited wait, the opposite of the intent.
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectMs))
                .setSocketTimeout(Timeout.ofMilliseconds(deadline.phaseTimeoutMs(properties.totalTimeout().toMillis())))
                .setTimeToLive(TimeValue.ofSeconds(1))
                .build();
        RequestConfig requestConfig = RequestConfig.copy(WebhookHttpClientConfig.baseRequestConfig())
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(
                        deadline.phaseTimeoutMs(properties.connectTimeout().toMillis())))
                .setResponseTimeout(Timeout.ofMilliseconds(
                        deadline.phaseTimeoutMs(properties.totalTimeout().toMillis())))
                .build();
        HttpClientContext context = HttpClientContext.create();
        context.setRequestConfig(requestConfig);

        // The watchdog is what makes the budget a deadline rather than a sum of per-phase ceilings: it aborts the
        // exchange from another thread at the moment the budget expires, whichever phase is blocking — pool lease,
        // connect, TLS handshake, waiting for status or header bytes, or a body read. Per-phase timeouts alone are
        // computed before the request starts and cannot react once it is under way.
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(
                () -> abortQuietly(post, request), Math.max(1L, deadline.remainingMs()), TimeUnit.MILLISECONDS);
        try {
            return connectionBudget.withConfig(connectionConfig, () ->
                    dnsResolver.withPinned(request.destination().url().host(), request.destination().addresses(),
                            () -> client.execute(post, context, response -> readResponse(post, response, deadline))));
        } catch (Exception e) {
            return classifyTransportFailure(e, post, request, deadline);
        } finally {
            watchdog.cancel(false);
            // The exchange is over: cancel the request so nothing keeps a socket or a pool lease if the handler
            // returned early from a truncated read. Connection reuse is disabled, so the connection is discarded.
            post.abort();
        }
    }

    private static void abortQuietly(HttpPost post, WebhookSendRequest request) {
        try {
            if (!post.isAborted()) {
                // Aborting closes the connection, so a cancelled attempt stops sending and stops receiving; it does
                // not keep streaming in the background after the caller has its outcome.
                post.abort();
                log.warn("Webhook attempt aborted at its deadline (deliveryId={}, host={})",
                        request.deliveryId(), request.destination().url().host());
            }
        } catch (RuntimeException e) {
            log.debug("Aborting a webhook attempt at its deadline failed: {}", e.getClass().getName());
        }
    }

    private WebhookSendOutcome readResponse(HttpPost post, ClassicHttpResponse response, WebhookDeadline deadline)
            throws IOException {
        int status = response.getCode();
        String retryAfter = retryAfter(response);
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            return WebhookSendOutcome.response(status, null, false, deadline.elapsedMs(), retryAfter);
        }
        int cap = properties.maxResponseBytes();
        byte[] buffer = new byte[Math.min(4096, cap)];
        byte[] collected = new byte[cap];
        int total = 0;
        boolean truncated = false;
        InputStream in = null;
        try {
            in = entity.getContent();
            while (total < cap) {
                if (deadline.expired()) {
                    // The status is already known; a slow or trickling body must not extend the attempt.
                    truncated = true;
                    break;
                }
                int read = in.read(buffer, 0, Math.min(buffer.length, cap - total));
                if (read < 0) {
                    break;
                }
                System.arraycopy(buffer, 0, collected, total, read);
                total += read;
                if (total >= cap) {
                    truncated = true;
                }
            }
        } catch (IOException e) {
            // A failed body read says nothing about whether the receiver accepted the message: the status does.
            truncated = true;
        } finally {
            if (truncated) {
                // Abort BEFORE the stream is closed, and detach the entity. Both matter: closing a half-read entity
                // makes the client read the rest so the connection could be reused, and so does the client's own
                // post-handler consume. Measured on a trickling receiver with a 900 ms budget: 61 s before this
                // abort, 0.9 s after it. Aborting discards the connection instead, which is correct anyway —
                // connection reuse is disabled for webhook delivery.
                post.abort();
                response.setEntity(null);
            }
            closeQuietly(in);
        }
        String snippet = total == 0 ? null : bounded(new String(collected, 0, total, StandardCharsets.UTF_8), truncated);
        return WebhookSendOutcome.response(status, snippet, truncated, deadline.elapsedMs(), retryAfter);
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            // Closing a connection we are abandoning anyway; nothing here changes the attempt's outcome.
            log.debug("Closing a truncated webhook response failed: {}", e.getClass().getName());
        }
    }

    /**
     * Bounds the snippet to {@code notify.webhook.stored-snippet-bytes} <em>UTF-8 bytes including the marker</em>, so
     * the value is storable exactly as returned. Truncation happens on a code-point boundary, so a multibyte
     * character is never cut in half and the stored text stays valid UTF-8.
     */
    private String bounded(String text, boolean readTruncated) {
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        int budget = properties.storedSnippetBytes();
        byte[] raw = oneLine.getBytes(StandardCharsets.UTF_8);
        boolean cut = readTruncated || raw.length > budget;
        if (!cut) {
            return oneLine;
        }
        String head = truncateToUtf8Bytes(oneLine, budget - MARKER_BYTES);
        return head + TRUNCATION_MARKER;
    }

    /** Longest prefix of {@code text} whose UTF-8 form fits {@code maxBytes}, never splitting a code point. */
    static String truncateToUtf8Bytes(String text, int maxBytes) {
        if (maxBytes <= 0) {
            return "";
        }
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        if (raw.length <= maxBytes) {
            return text;
        }
        int end = 0;
        int bytes = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int width = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + width > maxBytes) {
                break;
            }
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return text.substring(0, end);
    }

    /** Digits or an HTTP-date only, length-capped: a receiver header is untrusted text in our own attempt row. */
    private static String retryAfter(ClassicHttpResponse response) {
        Header header = response.getFirstHeader("Retry-After");
        if (header == null || header.getValue() == null) {
            return null;
        }
        String value = header.getValue().trim();
        if (value.isEmpty() || value.length() > MAX_RETRY_AFTER_CHARS) {
            return null;
        }
        if (!value.matches("[A-Za-z0-9 ,:+-]+")) {
            return null;
        }
        return value;
    }

    private WebhookSendOutcome classifyTransportFailure(
            Exception e, HttpPost post, WebhookSendRequest request, WebhookDeadline deadline) {
        long latency = deadline.elapsedMs();
        String host = request.destination().url().host();

        // The watchdog's abort surfaces as whatever I/O failure the current phase produces — a closed socket, an
        // interrupted read — not as a socket timeout. Mid-flight, the watchdog is the only thing that aborts this
        // request (the abort in the finally block runs after the outcome is decided), so an aborted request means
        // the budget ran out: TIMEOUT, whatever shape the exception took. The deadline is deliberately NOT re-checked
        // here: the watchdog can fire a fraction of a millisecond before `expired()` flips, and that race previously
        // let a cancelled attempt be reported as UNREACHABLE.
        if (post.isAborted()) {
            return failure(WebhookSendOutcome.Failure.TIMEOUT,
                    "Attempt cancelled at its deadline", host, e, latency);
        }

        Throwable cause = e;
        for (int depth = 0; depth < 8 && cause != null; depth++, cause = cause.getCause()) {
            if (cause instanceof SSLException) {
                return failure(WebhookSendOutcome.Failure.TLS, "TLS failure contacting the receiver", host, e, latency);
            }
            if (cause instanceof ConnectTimeoutException || cause instanceof SocketTimeoutException
                    || cause instanceof InterruptedIOException) {
                return failure(WebhookSendOutcome.Failure.TIMEOUT, "Receiver did not answer within the budget", host, e, latency);
            }
            if (cause instanceof UnknownHostException) {
                // Also the fail-closed path of PinnedDnsResolver: an unpinned host is unresolvable by construction.
                return failure(WebhookSendOutcome.Failure.UNREACHABLE, "Receiver host could not be resolved", host, e, latency);
            }
            if (cause instanceof ProtocolException) {
                return failure(WebhookSendOutcome.Failure.PROTOCOL, "Receiver sent an unparsable response", host, e, latency);
            }
            if (cause instanceof IOException) {
                return failure(WebhookSendOutcome.Failure.UNREACHABLE, "Receiver could not be reached", host, e, latency);
            }
        }
        return failure(WebhookSendOutcome.Failure.UNREACHABLE, "Unexpected transport failure", host, e, latency);
    }

    private static WebhookSendOutcome failure(
            WebhookSendOutcome.Failure failure, String detail, String host, Exception e, long latency) {
        // Host and exception class only: a receiver's error text is not ours to log.
        log.warn("Webhook attempt failed ({}) for host {}: {}", failure, host, e.getClass().getName());
        return WebhookSendOutcome.failure(failure, detail, latency);
    }
}
