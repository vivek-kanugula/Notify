package com.notify.delivery.provider.resend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.provider.EmailProvider;
import com.notify.delivery.provider.EmailSendRequest;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Resend adapter: {@code POST {base-url}/emails} with {@code Idempotency-Key}. Never touches the database.
 *
 * <p>Timeouts (confirmed decision 6): HTTP connect 2 s; Spring request-factory read timeout 8 s (response headers).
 * Because a JDK-client read timeout does not bound a stalled or trickling response <em>body</em>, the body is read
 * through a 64 KB bounded reader under a total wall-clock budget (connect + read); on expiry the stream is closed and
 * the late bytes are ignored. A timeout, oversize or malformed 2xx, or lost response may mean Resend accepted the
 * message, so these are retryable with the unchanged idempotency key; exactly-once delivery is not claimed.
 *
 * <p>Never logged: API key, Authorization header, notification body, recipient.
 */
@Component
@ConditionalOnProperty(prefix = "notify.email", name = "provider", havingValue = "resend")
public class ResendEmailProvider implements EmailProvider {

    public static final String NAME = "resend";

    private static final Logger log = LoggerFactory.getLogger(ResendEmailProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The explicit bound on outstanding body readers. Sized at the EMAIL bulkhead's default so a saturated
     * provider cannot starve readers before the bulkhead has already refused the call, with headroom for a reader
     * that is still unwinding from a previous attempt's timeout.
     */
    static final int READER_THREADS = 8;

    private final String apiKey;
    private final int maxResponseBytes;
    private final long totalBudgetNanos;
    private final RestClient restClient;
    /**
     * Readers for the bounded response body, on an <b>explicitly bounded</b> pool (/ , ).
     *
     * <p>This was {@code Executors.newVirtualThreadPerTaskExecutor()}. On a timeout the reader is cancelled and the
     * stream closed, but cancellation is <b>not proof that the task terminated</b> - a reader blocked in a socket
     * read keeps running until the close takes effect. With an unbounded factory, repeated timeouts could therefore
     * accumulate live readers without limit. The pool is now fixed, so outstanding readers can never exceed
     * {@code READER_THREADS}; when they are all occupied, a submit is refused and the attempt is reported as a
     * timeout - the same outcome as a stalled read, reached without queueing work indefinitely. The caller never
     * waits for an abandoned reader to finish: it only ever waits out its own deadline.
     */
    private final ExecutorService bodyReaders = new java.util.concurrent.ThreadPoolExecutor(
            READER_THREADS, READER_THREADS, 0L, TimeUnit.MILLISECONDS,
            new java.util.concurrent.SynchronousQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "notify-resend-body-reader");
                thread.setDaemon(true);
                return thread;
            },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    /** The resilience seam (/ ). Null in the properties-only constructor: call the provider directly. */
    private final com.notify.delivery.resilience.ProviderCallGuard guard;
    private final io.github.resilience4j.circuitbreaker.CircuitBreaker breaker;
    private final io.github.resilience4j.bulkhead.Bulkhead bulkhead;
    private final io.github.resilience4j.ratelimiter.RateLimiter limiter;

    /** Without the resilience seam, for tests that exercise the HTTP behaviour in isolation. */
    public ResendEmailProvider(ResendProperties properties) {
        this(properties, null, null, null, null);
    }

    /**
     * Every resilience collaborator is an {@link org.springframework.beans.factory.ObjectProvider} on purpose: this
     * provider must exist whether or not resilience is configured. A narrow context - the production provider-guard
     * slice, for instance - wires the provider without any of them and calls the API directly, which is also what
     * makes "is the right provider selected?" answerable independently of "is resilience configured?".
     */
    @Autowired
    public ResendEmailProvider(
            ResendProperties properties,
            org.springframework.beans.factory.ObjectProvider<com.notify.delivery.resilience.ProviderCallGuard> guard,
            @org.springframework.beans.factory.annotation.Qualifier("resendCircuitBreaker")
            org.springframework.beans.factory.ObjectProvider<io.github.resilience4j.circuitbreaker.CircuitBreaker>
                    breaker,
            @org.springframework.beans.factory.annotation.Qualifier("emailBulkhead")
            org.springframework.beans.factory.ObjectProvider<io.github.resilience4j.bulkhead.Bulkhead> bulkhead,
            org.springframework.beans.factory.ObjectProvider<io.github.resilience4j.ratelimiter.RateLimiter> limiter
    ) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "notify.resend.api-key (env NOTIFY_RESEND_API_KEY) is required when notify.email.provider=resend");
        }
        this.apiKey = properties.apiKey().trim();
        this.maxResponseBytes = properties.maxResponseBytes();
        this.totalBudgetNanos = properties.connectTimeout().plus(properties.readTimeout()).toNanos();

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
        // An absent collaborator is a normal configuration, not a failure: no guard means call directly, and the
        // limiter bean is null whenever the rate limit is disabled.
        this.guard = guard == null ? null : guard.getIfAvailable();
        this.breaker = breaker == null ? null : breaker.getIfAvailable();
        this.bulkhead = bulkhead == null ? null : bulkhead.getIfAvailable();
        this.limiter = limiter == null ? null : limiter.getIfAvailable();
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Sends under the resilience guard when one is wired (/ , ).
     *
     * <p>Serialization happens first and outside the guard: a payload that cannot be serialized is a permanent
     * validation failure of ours, not a provider event, and must not consume a rate-limit permit or count toward a
     * breaker. A guard refusal escapes as {@code ProviderDeferredException} before the request is made.
     */
    @Override
    public DeliveryResult send(EmailSendRequest request) {
        if (guard == null) {
            return sendDirectly(request);
        }
        return guard.execute(
                "resend",
                breaker,
                bulkhead,
                limiter,
                // The mapper already decided which outcomes are transient; the breaker records that judgement
                // rather than inventing a second one, so a permanent 4xx never opens it.
                result -> result instanceof DeliveryResult.RetryableFailure,
                () -> sendDirectly(request));
    }

    private DeliveryResult sendDirectly(EmailSendRequest request) {
        byte[] payload;
        try {
            Map<String, Object> bodyMap = new LinkedHashMap<>();
            bodyMap.put("from", nullToEmpty(request.from()));
            bodyMap.put("to", List.of(nullToEmpty(request.to())));
            bodyMap.put("subject", nullToEmpty(request.subject()));
            bodyMap.put("text", nullToEmpty(request.text()));
            if (request.replyTo() != null && !request.replyTo().isBlank()) {
                bodyMap.put("reply_to", request.replyTo().trim());
            }
            payload = MAPPER.writeValueAsBytes(bodyMap);
        } catch (JsonProcessingException e) {
            return new DeliveryResult.PermanentFailure("VALIDATION_FAILED", "Email could not be serialized", null, null);
        }

        long deadlineNanos = System.nanoTime() + totalBudgetNanos;
        try {
            return restClient.post()
                    .uri("/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header("Idempotency-Key", request.idempotencyKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .exchange((clientRequest, response) -> handle(response, deadlineNanos), true);
        } catch (ResourceAccessException e) {
            if (Thread.currentThread().isInterrupted()) {
                return unavailable("Provider call interrupted");
            }
            if (hasCause(e, HttpTimeoutException.class)) {
                return new DeliveryResult.RetryableFailure("PROVIDER_TIMEOUT", "Provider request timed out", null, null);
            }
            return unavailable("Provider could not be reached");
        } catch (RuntimeException e) {
            // Ambiguous remote effect: retry is safe because the idempotency key is unchanged.
            log.warn("Unexpected failure calling email provider: {}", e.getClass().getName());
            return unavailable("Unexpected error calling provider");
        }
    }

    private DeliveryResult handle(ClientHttpResponse response, long deadlineNanos) throws IOException {
        int status = response.getStatusCode().value();
        BoundedBody body = readBounded(response.getBody(), deadlineNanos);
        if (body.timedOut()) {
            if (status >= 200 && status < 300) {
                return new DeliveryResult.RetryableFailure("PROVIDER_TIMEOUT", "Provider response body timed out", status, null);
            }
            return ResendResponseMapper.map(status, null, true);
        }
        return ResendResponseMapper.map(status, body.text(), body.oversize());
    }

    /**
     * Package-private, not private, as the one test seam this bound needs: requires verifying that repeated
     * timeouts cannot accumulate work beyond the reader bound, and that cannot be shown through {@code send} alone -
     * a reader that ignores both cancellation and {@code close} is exactly the case under test, and no HTTP fixture
     * can produce one. The method, the pool, the rejection policy and the timeout semantics are the production ones.
     */
    BoundedBody readBounded(InputStream in, long deadlineNanos) {
        CompletableFuture<BoundedBody> future;
        try {
            future = startBoundedRead(in, deadlineNanos);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Every reader slot is occupied, which means earlier attempts' readers are still unwinding. Refusing
            // here is what keeps outstanding readers bounded; the attempt is reported exactly as a stalled read is.
            closeQuietly(in);
            log.warn("Response-body readers are saturated ({} outstanding); treating this attempt as a timeout",
                    READER_THREADS);
            return new BoundedBody(null, false, true);
        }

        long remainingNanos = Math.max(0, deadlineNanos - System.nanoTime());
        try {
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            closeQuietly(in);
            return new BoundedBody(null, false, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            closeQuietly(in);
            return new BoundedBody(null, false, true);
        } catch (ExecutionException e) {
            closeQuietly(in);
            return new BoundedBody(null, false, true);
        }
    }

    private CompletableFuture<BoundedBody> startBoundedRead(InputStream in, long deadlineNanos) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                boolean oversize = false;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    int room = maxResponseBytes - out.size();
                    if (read > room) {
                        out.write(buffer, 0, Math.max(room, 0));
                        oversize = true;
                        break;
                    }
                    out.write(buffer, 0, read);
                }
                return new BoundedBody(out.toString(StandardCharsets.UTF_8), oversize, false);
            } catch (IOException e) {
                return new BoundedBody(null, false, true);
            }
        }, bodyReaders);
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // best effort: closing aborts a stalled exchange
        }
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    private static DeliveryResult unavailable(String message) {
        return new DeliveryResult.RetryableFailure("PROVIDER_UNAVAILABLE", message, null, null);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @PreDestroy
    public void shutdown() {
        bodyReaders.shutdownNow();
    }

    /** Result of the bounded body read. {@code timedOut} also covers an IO failure while reading. */
    record BoundedBody(String text, boolean oversize, boolean timedOut) {
    }

}
