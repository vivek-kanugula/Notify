package com.notify.delivery.provider.webhook;

import com.notify.delivery.resilience.HostBreakerRegistry;
import com.notify.delivery.resilience.ProviderCallGuard;
import com.notify.delivery.resilience.ProviderDeferredException;
import com.notify.delivery.resilience.ResilienceProperties;
import com.notify.webhook.config.WebhookProperties;
import com.notify.webhook.security.DestinationResolver.PinnedDestination;
import com.notify.webhook.security.WebhookUrlPolicy.ValidatedUrl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The <b>production guarded send path</b>, against a real loopback receiver and real Resilience4j primitives
 * (/ , ; repair of the review finding F1).
 *
 * <p><b>Why this test and not a predicate test.</b> The breaker's failure classification used to be a second,
 * hand-maintained list inside {@code WebhookSender}, and it diverged from {@link WebhookResponseMapper} in both
 * directions: a {@code 408} was retryable for the mapper but invisible to the breaker, and a {@code PROTOCOL}
 * failure was <em>permanent</em> for the mapper yet counted against the breaker - so one tenant's malformed
 * receiver could open the <em>host</em> breaker and defer another tenant's deliveries to it. A test of the private
 * predicate would not have caught that, because the predicate was self-consistent; only driving real responses
 * through the real sender, the real guard and a real breaker shows the two classifications agreeing.
 *
 * <p>Nothing here is stubbed: an actual {@code HttpServer} answers, the configured Apache client sends, and the
 * breaker and bulkhead are the same classes the application wires, from the resolved 2.3.0 jars.
 */
class GuardedWebhookSenderTest {

    private static final int FAILURES_TO_OPEN = 4;

    private HttpServer server;
    private int port;
    private final AtomicInteger requests = new AtomicInteger();

    private WebhookProperties properties;
    private PinnedDnsResolver dnsResolver;
    private WebhookConnectionBudget budget;
    private CloseableHttpClient client;
    private HostBreakerRegistry hostBreakers;
    private Bulkhead bulkhead;
    private WebhookSender sender;

    /** The shape every case shares: a small count-based window so four calls can decide. */
    private static ResilienceProperties enabled() {
        return new ResilienceProperties(
                true, 50,
                new ResilienceProperties.Breaker(50, 5, FAILURES_TO_OPEN, Duration.ofMinutes(5), 1),
                new ResilienceProperties.Bulkhead(1, 1),
                new ResilienceProperties.RateLimit(0, Duration.ofSeconds(1)),
                new ResilienceProperties.HostRegistry(16, Duration.ofMinutes(30)),
                Duration.ofMinutes(5));
    }

    private static CircuitBreakerConfig breakerConfig(ResilienceProperties props) {
        ResilienceProperties.Breaker breaker = props.breaker();
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(breaker.failureRateThresholdPercent())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(breaker.slidingWindowSize())
                .minimumNumberOfCalls(breaker.minimumCalls())
                .waitDurationInOpenState(breaker.waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(breaker.halfOpenPermittedCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = server.getAddress().getPort();
        server.start();

        properties = new WebhookProperties(null, Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofSeconds(2), 8192, 4096, "Notify-Webhook/1.0 (+test)", List.of());
        dnsResolver = new PinnedDnsResolver();
        WebhookHttpClientConfig config = new WebhookHttpClientConfig();
        budget = config.webhookConnectionBudget(properties);
        client = config.webhookHttpClient(properties, dnsResolver, budget);

        ResilienceProperties props = enabled();
        hostBreakers = new HostBreakerRegistry(
                breakerConfig(props), props.hostRegistry(), Clock.fixed(java.time.Instant.now(), ZoneOffset.UTC));
        bulkhead = Bulkhead.of("webhook", BulkheadConfig.custom()
                .maxConcurrentCalls(props.bulkhead().webhook())
                .maxWaitDuration(Duration.ZERO)
                .build());
        sender = new WebhookSender(client, dnsResolver, budget, properties,
                new ProviderCallGuard(props, new SimpleMeterRegistry()), hostBreakers, bulkhead);
    }

    @AfterEach
    void stopServer() throws IOException {
        client.close();
        server.stop(0);
    }

    private void handle(String path, int status, String body) {
        server.createContext(path, exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, status, body);
        });
    }

    /**
     * A raw socket that answers with bytes that are not HTTP at all, so the client raises a protocol error - the
     * {@code PROTOCOL} failure the mapper classifies <b>permanent</b>. {@code HttpServer} cannot produce this: it
     * always writes a valid status line.
     */
    private java.net.ServerSocket rawGarbageServer(AtomicInteger seen) throws IOException {
        java.net.ServerSocket raw = new java.net.ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread accept = new Thread(() -> {
            while (!raw.isClosed()) {
                try (java.net.Socket socket = raw.accept()) {
                    seen.incrementAndGet();
                    socket.getInputStream().read(new byte[4096]);
                    // An HTTP-shaped head whose status code is not a number: the client raises a ProtocolException,
                    // which is what PROTOCOL means here. Two near misses, deliberately avoided: raw non-HTTP bytes
                    // surface as a plain IOException (UNREACHABLE), and a malformed *body* is tolerated by the
                    // bounded reader, leaving a perfectly good 2xx.
                    socket.getOutputStream().write(("HTTP/1.1 ABC Not-A-Status\r\n"
                            + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                } catch (IOException e) {
                    return;                       // the socket was closed by the fixture
                }
            }
        }, "garbage-receiver");
        accept.setDaemon(true);
        accept.start();
        return raw;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private WebhookSendRequest request(String path) {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        ValidatedUrl url = new ValidatedUrl(uri, "127.0.0.1", port, false, true);
        PinnedDestination destination = new PinnedDestination(url, List.of(InetAddress.getLoopbackAddress()));
        return new WebhookSendRequest(destination, "delivery-1",
                "{\"deliveryId\":\"delivery-1\"}".getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private CircuitBreaker.State stateAfter(String path, int times) {
        for (int i = 0; i < times; i++) {
            sender.send(request(path));
        }
        return hostBreakers.forHost("127.0.0.1").getState();
    }

    @Test
    @DisplayName("an allowed guarded call reaches the receiver, succeeds and leaves the breaker closed")
    void anAllowedCallGoesThrough() {
        handle("/ok", 202, "{\"ok\":true}");

        WebhookSendOutcome outcome = sender.send(request("/ok"));

        assertThat(outcome.httpStatus()).isEqualTo(202);
        assertThat(outcome.failure()).isEqualTo(WebhookSendOutcome.Failure.NONE);
        assertThat(requests.get()).as("the guard did not swallow the request").isEqualTo(1);
        assertThat(hostBreakers.forHost("127.0.0.1").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls())
                .as("the permit is released on the success path too")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a 429 counts: the breaker opens and the next call never reaches the receiver")
    void rateLimitedResponsesOpenTheBreaker() {
        handle("/429", 429, "slow down");

        assertThat(stateAfter("/429", FAILURES_TO_OPEN)).isEqualTo(CircuitBreaker.State.OPEN);

        int sentBefore = requests.get();
        assertThatThrownBy(() -> sender.send(request("/429")))
                .isInstanceOf(ProviderDeferredException.class)
                .extracting(e -> ((ProviderDeferredException) e).code())
                .isEqualTo(ProviderCallGuard.CIRCUIT_OPEN);
        assertThat(requests.get()).as("an open breaker opens no socket").isEqualTo(sentBefore);
    }

    @Test
    @DisplayName("a 503 and a 500 count against the breaker")
    void serverErrorsOpenTheBreaker() {
        handle("/503", 503, null);
        assertThat(stateAfter("/503", FAILURES_TO_OPEN)).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("F1 a 408 counts against the breaker: the mapper calls it retryable, so the breaker must agree")
    void timeoutStatusCountsAgainstTheBreaker() {
        handle("/408", 408, null);

        assertThat(stateAfter("/408", FAILURES_TO_OPEN))
                .as("the old hand-maintained rule listed only 429 and 5xx, so a 408 never opened a breaker")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("F1 a transient transport failure counts: an unreachable receiver opens the breaker")
    void transportFailuresCountAgainstTheBreaker() throws IOException {
        int closedPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        URI uri = URI.create("http://127.0.0.1:" + closedPort + "/hook");
        ValidatedUrl url = new ValidatedUrl(uri, "127.0.0.1", closedPort, false, true);
        PinnedDestination destination = new PinnedDestination(url, List.of(InetAddress.getLoopbackAddress()));
        WebhookSendRequest refused = new WebhookSendRequest(destination, "delivery-1",
                "{}".getBytes(StandardCharsets.UTF_8), Map.of());

        for (int i = 0; i < FAILURES_TO_OPEN; i++) {
            WebhookSendOutcome outcome = sender.send(refused);
            assertThat(outcome.failure()).isEqualTo(WebhookSendOutcome.Failure.UNREACHABLE);
        }

        assertThat(hostBreakers.forHost("127.0.0.1").getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("F1 a permanent 4xx never opens the breaker, however many of them a receiver sends")
    void permanentClientErrorsLeaveTheBreakerClosed() {
        handle("/404", 404, "no such hook");

        assertThat(stateAfter("/404", FAILURES_TO_OPEN * 3))
                .as("a tenant's own wrong path must not defer another tenant's deliveries to this host")
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(requests.get()).isEqualTo(FAILURES_TO_OPEN * 3);
    }

    @Test
    @DisplayName("F1 a redirect never opens the breaker: it is permanent, and it is never followed")
    void redirectsLeaveTheBreakerClosed() {
        server.createContext("/redirect", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            respond(exchange, 302, null);
        });

        assertThat(stateAfter("/redirect", FAILURES_TO_OPEN * 3)).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * A receiver that answers with something the client cannot parse as HTTP, driven through the guarded sender.
     *
     * <p>What it demonstrates is narrower than it looks, and that is the point: this client reports a bad status
     * line as a plain {@code IOException}, so the outcome is {@code UNREACHABLE} - transient, counted. A genuine
     * {@code PROTOCOL} outcome is not reachable through a real socket here, which is why the parity case below
     * pins {@code PROTOCOL} and {@code BLOCKED} against the mapper instead of pretending a transport test covers
     * them.
     */
    @Test
    @DisplayName("F1 a receiver answering unparsable bytes is transient here, and the guarded path survives it")
    void unparsableBytesAreTransientThroughTheGuardedPath() throws IOException {
        AtomicInteger seen = new AtomicInteger();
        try (java.net.ServerSocket raw = rawGarbageServer(seen)) {
            int rawPort = raw.getLocalPort();
            URI uri = URI.create("http://127.0.0.1:" + rawPort + "/hook");
            ValidatedUrl url = new ValidatedUrl(uri, "127.0.0.1", rawPort, false, true);
            PinnedDestination destination = new PinnedDestination(url, List.of(InetAddress.getLoopbackAddress()));
            WebhookSendRequest garbage = new WebhookSendRequest(destination, "delivery-1",
                    "{}".getBytes(StandardCharsets.UTF_8), Map.of());

            for (int i = 0; i < FAILURES_TO_OPEN; i++) {
                WebhookSendOutcome outcome = sender.send(garbage);
                assertThat(outcome.failure()).isEqualTo(WebhookSendOutcome.Failure.UNREACHABLE);
                assertThat(WebhookSender.countsAgainstTheBreaker(outcome))
                        .as("the sender and the mapper must agree about whatever the transport produced")
                        .isEqualTo(WebhookResponseMapper.map(outcome)
                                instanceof com.notify.delivery.model.DeliveryResult.RetryableFailure);
            }
            assertThat(seen.get()).isEqualTo(FAILURES_TO_OPEN);
        }
    }

    /**
     * The whole classification, as one table: the breaker's verdict must equal the mapper's for every outcome,
     * including the two the transport cannot produce.
     *
     * <p>{@code PROTOCOL} is the case the old hand-maintained rule got wrong in the damaging direction - permanent
     * for the mapper, counted by the breaker - so one tenant's malformed receiver could open the <em>host</em>
     * breaker and defer another tenant's deliveries to it. {@code BLOCKED} is decided by the destination resolver
     * before the sender runs, so it can only ever reach the breaker if some future code path starts producing it.
     */
    @Test
    @DisplayName("F1 the breaker's verdict equals the mapper's for every outcome, PROTOCOL and BLOCKED included")
    void breakerVerdictMatchesTheMapperForEveryOutcome() {
        List<WebhookSendOutcome> outcomes = List.of(
                WebhookSendOutcome.response(200, "{}", false, 1),
                WebhookSendOutcome.response(204, null, false, 1),
                WebhookSendOutcome.response(302, null, false, 1),
                WebhookSendOutcome.response(400, null, false, 1),
                WebhookSendOutcome.response(404, null, false, 1),
                WebhookSendOutcome.response(408, null, false, 1),
                WebhookSendOutcome.response(429, null, false, 1),
                WebhookSendOutcome.response(500, null, false, 1),
                WebhookSendOutcome.response(503, null, false, 1),
                WebhookSendOutcome.response(599, null, false, 1),
                WebhookSendOutcome.failure(WebhookSendOutcome.Failure.TIMEOUT, "d", 1),
                WebhookSendOutcome.failure(WebhookSendOutcome.Failure.UNREACHABLE, "d", 1),
                WebhookSendOutcome.failure(WebhookSendOutcome.Failure.TLS, "d", 1),
                WebhookSendOutcome.failure(WebhookSendOutcome.Failure.PROTOCOL, "d", 1),
                WebhookSendOutcome.failure(WebhookSendOutcome.Failure.BLOCKED, "d", 1));

        for (WebhookSendOutcome outcome : outcomes) {
            boolean retryable = WebhookResponseMapper.map(outcome)
                    instanceof com.notify.delivery.model.DeliveryResult.RetryableFailure;
            assertThat(WebhookSender.countsAgainstTheBreaker(outcome))
                    .as("outcome %s", outcome)
                    .isEqualTo(retryable);
        }

        // Named explicitly, because these four are the ones a second rule got wrong or would be tempted to:
        assertThat(WebhookSender.countsAgainstTheBreaker(
                WebhookSendOutcome.failure(WebhookSendOutcome.Failure.PROTOCOL, "d", 1))).isFalse();
        assertThat(WebhookSender.countsAgainstTheBreaker(
                WebhookSendOutcome.failure(WebhookSendOutcome.Failure.BLOCKED, "d", 1))).isFalse();
        assertThat(WebhookSender.countsAgainstTheBreaker(WebhookSendOutcome.response(302, null, false, 1))).isFalse();
        assertThat(WebhookSender.countsAgainstTheBreaker(WebhookSendOutcome.response(404, null, false, 1))).isFalse();
        assertThat(WebhookSender.countsAgainstTheBreaker(WebhookSendOutcome.response(408, null, false, 1))).isTrue();
    }

    @Test
    @DisplayName("a full bulkhead refuses before any socket is opened")
    void aFullBulkheadOpensNoSocket() {
        handle("/busy", 200, "{}");
        assertThat(bulkhead.tryAcquirePermission()).isTrue();      // the only permit, taken by someone else
        try {
            assertThatThrownBy(() -> sender.send(request("/busy")))
                    .isInstanceOf(ProviderDeferredException.class)
                    .extracting(e -> ((ProviderDeferredException) e).code())
                    .isEqualTo(ProviderCallGuard.BULKHEAD_FULL);
            assertThat(requests.get()).isZero();
        } finally {
            bulkhead.releasePermission();
        }
    }
}
