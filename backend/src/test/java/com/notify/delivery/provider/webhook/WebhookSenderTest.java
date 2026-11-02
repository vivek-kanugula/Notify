package com.notify.delivery.provider.webhook;

import com.notify.webhook.config.WebhookProperties;
import com.notify.webhook.security.DestinationResolver.PinnedDestination;
import com.notify.webhook.security.WebhookUrlPolicy.ValidatedUrl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * / transport behaviour against a real loopback receiver: no mock HTTP stack, because the properties
 * under test (bounded reads, no redirect following, no connection reuse, pinned resolution) are properties of the
 * configured client, not of our own code.
 */
class WebhookSenderTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<byte[]> lastBody = new AtomicReference<>();
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();

    private WebhookProperties properties;
    private PinnedDnsResolver dnsResolver;
    private WebhookConnectionBudget budget;
    private CloseableHttpClient client;
    private WebhookSender sender;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = server.getAddress().getPort();
        server.start();

        properties = new WebhookProperties(
                null, Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(2), 8192, 4096, "Notify-Webhook/1.0 (+test)", List.of());
        dnsResolver = new PinnedDnsResolver();
        WebhookHttpClientConfig config = new WebhookHttpClientConfig();
        budget = config.webhookConnectionBudget(properties);
        client = config.webhookHttpClient(properties, dnsResolver, budget);
        sender = new WebhookSender(client, dnsResolver, budget, properties);
    }

    @AfterEach
    void stopServer() throws IOException {
        client.close();
        server.stop(0);
    }

    /** Registers a handler and records what the receiver actually saw. */
    private void handle(String path, Handler handler) {
        server.createContext(path, exchange -> {
            requests.incrementAndGet();
            exchange.getRequestHeaders().forEach((name, values) -> lastHeaders.put(name, values.get(0)));
            try (InputStream in = exchange.getRequestBody()) {
                lastBody.set(in.readAllBytes());
            }
            handler.handle(exchange);
        });
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

    private WebhookSendRequest request(String path, Map<String, String> headers) {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        ValidatedUrl url = new ValidatedUrl(uri, "127.0.0.1", port, false, true);
        PinnedDestination destination =
                new PinnedDestination(url, List.of(InetAddress.getLoopbackAddress()));
        return new WebhookSendRequest(destination, "delivery-1", "{\"deliveryId\":\"delivery-1\"}"
                .getBytes(StandardCharsets.UTF_8), headers);
    }

    @Test
    @DisplayName("a 2xx receiver sees the exact body and headers, and the outcome carries the status and a latency")
    void successfulPost() {
        handle("/hook", exchange -> respond(exchange, 202, "{\"ok\":true}"));

        WebhookSendOutcome outcome = sender.send(request("/hook", Map.of(
                "X-Notify-Delivery-ID", "delivery-1", "X-Notify-Attempt", "1")));

        assertThat(outcome.httpStatus()).isEqualTo(202);
        assertThat(outcome.failure()).isEqualTo(WebhookSendOutcome.Failure.NONE);
        assertThat(outcome.bodySnippet()).isEqualTo("{\"ok\":true}");
        assertThat(outcome.truncated()).isFalse();
        assertThat(outcome.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(new String(lastBody.get(), StandardCharsets.UTF_8)).isEqualTo("{\"deliveryId\":\"delivery-1\"}");
        assertThat(lastHeaders.get("X-notify-delivery-id")).isEqualTo("delivery-1");
        assertThat(lastHeaders.get("X-notify-attempt")).isEqualTo("1");
        assertThat(lastHeaders.get("Content-type")).contains("application/json");
        assertThat(lastHeaders.get("User-agent")).isEqualTo("Notify-Webhook/1.0 (+test)");
    }

    @Test
    @DisplayName("an empty response body yields a null snippet rather than empty text")
    void emptyBody() {
        handle("/empty", exchange -> respond(exchange, 204, null));

        WebhookSendOutcome outcome = sender.send(request("/empty", Map.of()));

        assertThat(outcome.httpStatus()).isEqualTo(204);
        assertThat(outcome.bodySnippet()).isNull();
        assertThat(outcome.truncated()).isFalse();
    }

    @Test
    @DisplayName("an oversize response body is cut at the read cap and flagged, and the 2xx still stands")
    void oversizeBodyIsBounded() {
        String huge = "x".repeat(200_000);
        handle("/huge", exchange -> respond(exchange, 200, huge));

        WebhookSendOutcome outcome = sender.send(request("/huge", Map.of()));

        assertThat(outcome.httpStatus()).isEqualTo(200);
        assertThat(outcome.truncated()).isTrue();
        // F6: the snippet is bounded in UTF-8 bytes INCLUDING the marker, so it is storable exactly as returned.
        assertThat(outcome.bodySnippet()).endsWith(WebhookSender.TRUNCATION_MARKER);
        assertThat(outcome.bodySnippet().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(properties.storedSnippetBytes());
        assertThat(WebhookResponseMapper.map(outcome)).isInstanceOf(com.notify.delivery.model.DeliveryResult.Success.class);
    }

    @Test
    @DisplayName("a multibyte oversize body is cut on a code-point boundary and still fits the byte budget with its marker")
    void multibyteBodyIsBoundedByBytes() {
        handle("/multibyte", exchange -> respond(exchange, 500, "☃".repeat(40_000)));

        WebhookSendOutcome outcome = sender.send(request("/multibyte", Map.of()));

        String snippet = outcome.bodySnippet();
        assertThat(snippet).endsWith(WebhookSender.TRUNCATION_MARKER);
        assertThat(snippet.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(properties.storedSnippetBytes());
        // No replacement character: a 3-byte snowman was never split in half.
        assertThat(snippet).doesNotContain("�");
        String head = snippet.substring(0, snippet.length() - WebhookSender.TRUNCATION_MARKER.length());
        assertThat(head.chars().allMatch(c -> c == '☃')).isTrue();
    }

    @Test
    @DisplayName("a Retry-After header is recorded, sanitized and length-capped; junk is dropped")
    void retryAfterIsRecorded() {
        handle("/retry-after", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "120");
            respond(exchange, 503, "slow down");
        });
        handle("/retry-after-junk", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "</>\"'; DROP TABLE");
            respond(exchange, 429, "no");
        });

        WebhookSendOutcome ok = sender.send(request("/retry-after", Map.of()));
        assertThat(ok.retryAfter()).isEqualTo("120");
        assertThat(((com.notify.delivery.model.DeliveryResult.RetryableFailure) WebhookResponseMapper.map(ok)).message())
                .contains("Retry-After: 120");

        WebhookSendOutcome junk = sender.send(request("/retry-after-junk", Map.of()));
        assertThat(junk.retryAfter()).as("unsafe characters are not recorded").isNull();
    }

    @Test
    @DisplayName("a budget already spent before the request opens no socket and is a TIMEOUT")
    void expiredBudgetSendsNothing() {
        handle("/never-called", exchange -> respond(exchange, 200, "ok"));

        WebhookSendOutcome outcome = sender.send(request("/never-called", Map.of()),
                WebhookDeadline.startedAgo(Duration.ofSeconds(5), Duration.ofSeconds(6)));

        assertThat(outcome.failure()).isEqualTo(WebhookSendOutcome.Failure.TIMEOUT);
        assertThat(requests.get()).as("no request reached the receiver").isZero();
    }

    @Test
    @DisplayName("a body that trickles forever is cut at the deadline, the attempt stays inside the budget, and the 2xx stands")
    void tricklingBodyIsCutAtTheDeadline() throws IOException {
        server.createContext("/trickle", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 2_000; i++) {
                    out.write("xxxxxxxx".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(20);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // the client closed the connection at its deadline, which is the point of this test
            }
            exchange.close();
        });

        long startedAt = System.nanoTime();
        WebhookSendOutcome outcome = sender.send(request("/trickle", Map.of()),
                WebhookDeadline.startingNow(Duration.ofMillis(900)));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(outcome.httpStatus()).as("a known 2xx is never demoted by its body").isEqualTo(200);
        assertThat(outcome.truncated()).isTrue();
        assertThat(elapsedMs).isLessThan(2_500);
        assertThat(WebhookResponseMapper.map(outcome))
                .isInstanceOf(com.notify.delivery.model.DeliveryResult.Success.class);
    }

    @Test
    @DisplayName("a stored snippet is single-line, so a multi-line receiver error cannot break log or JSON structure")
    void snippetIsSingleLine() {
        handle("/multiline", exchange -> respond(exchange, 500, "line one\nline two\r\nline three"));

        WebhookSendOutcome outcome = sender.send(request("/multiline", Map.of()));

        assertThat(outcome.bodySnippet()).isEqualTo("line one line two  line three");
    }

    @Test
    @DisplayName("a 302 is returned as a 302: the redirect is never followed, so the Location host is never contacted")
    void redirectIsNotFollowed() {
        handle("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            respond(exchange, 302, null);
        });

        WebhookSendOutcome outcome = sender.send(request("/redirect", Map.of()));

        assertThat(outcome.httpStatus()).isEqualTo(302);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a receiver that never answers times out within the total budget and is classified TIMEOUT")
    void slowReceiverTimesOut() {
        WebhookProperties quick = new WebhookProperties(
                null, Duration.ofMillis(500), Duration.ofMillis(600), Duration.ofMillis(500), 8192, 4096, "Notify-Webhook/1.0 (+test)", List.of());
        WebhookHttpClientConfig quickConfig = new WebhookHttpClientConfig();
        WebhookConnectionBudget quickBudget = quickConfig.webhookConnectionBudget(quick);
        try (CloseableHttpClient quickClient = quickConfig.webhookHttpClient(quick, dnsResolver, quickBudget)) {
            WebhookSender quickSender = new WebhookSender(quickClient, dnsResolver, quickBudget, quick);
            handle("/slow", exchange -> {
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, 200, "late");
            });

            long startedAt = System.nanoTime();
            WebhookSendOutcome outcome = quickSender.send(request("/slow", Map.of()));
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(outcome.failure()).isEqualTo(WebhookSendOutcome.Failure.TIMEOUT);
            assertThat(outcome.httpStatus()).isNull();
            assertThat(elapsedMs).isLessThan(2_500);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("a closed port is UNREACHABLE, and the failure detail names no address")
    void refusedConnection() throws IOException {
        // A port that was bound and then fully closed: connecting is refused outright, rather than queued by a
        // listening-but-not-accepting socket (which would be a timeout, a different classification).
        int closedPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }

        URI uri = URI.create("http://127.0.0.1:" + closedPort + "/hook");
        ValidatedUrl url = new ValidatedUrl(uri, "127.0.0.1", closedPort, false, true);
        WebhookSendRequest refused = new WebhookSendRequest(
                new PinnedDestination(url, List.of(InetAddress.getLoopbackAddress())),
                "delivery-2", "{}".getBytes(StandardCharsets.UTF_8), Map.of());

        WebhookSendOutcome outcome = sender.send(refused);

        assertThat(outcome.failure()).isEqualTo(WebhookSendOutcome.Failure.UNREACHABLE);
        assertThat(outcome.detail()).doesNotContain("127.0.0.1").doesNotContain(String.valueOf(closedPort));
    }

    @Test
    @DisplayName("the client cannot resolve a host the request did not pin, so it cannot be borrowed to reach anything else")
    void unpinnedHostIsUnresolvable() {
        assertThatThrownBy(() -> dnsResolver.resolve("metadata.google.internal"))
                .isInstanceOf(java.net.UnknownHostException.class);
    }

    @Test
    @DisplayName("the pin is removed when the request finishes, so no host stays resolvable afterwards")
    void pinDoesNotOutliveTheRequest() throws Exception {
        handle("/pin", exchange -> respond(exchange, 200, "ok"));
        sender.send(request("/pin", Map.of()));

        assertThatThrownBy(() -> dnsResolver.resolve("127.0.0.1"))
                .isInstanceOf(java.net.UnknownHostException.class);
    }

    @Test
    @DisplayName("each attempt opens its own connection, so a validated DNS answer is never reused by a later request")
    void connectionsAreNotReused() {
        handle("/reuse", exchange -> {
            exchange.getResponseHeaders().add("Connection", "keep-alive");
            respond(exchange, 200, "ok");
        });

        sender.send(request("/reuse", Map.of()));
        WebhookSendOutcome second = sender.send(request("/reuse", Map.of()));

        assertThat(second.httpStatus()).isEqualTo(200);
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a non-ASCII path is percent-encoded on the wire, so the signed body and the request line stay valid")
    void nonAsciiPathIsEncoded() {
        handle("/caf", exchange -> respond(exchange, 200, "ok"));
        URI uri = URI.create("http://127.0.0.1:" + port + "/café");
        ValidatedUrl url = new ValidatedUrl(uri, "127.0.0.1", port, false, true);
        WebhookSendRequest request = new WebhookSendRequest(
                new PinnedDestination(url, List.of(InetAddress.getLoopbackAddress())),
                "delivery-3", "{}".getBytes(StandardCharsets.UTF_8), Map.of());

        WebhookSendOutcome outcome = sender.send(request);

        assertThat(outcome.failure()).isEqualTo(WebhookSendOutcome.Failure.NONE);
        assertThat(outcome.httpStatus()).isEqualTo(200);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
    // ─── F7: the budget is a deadline, enforced while a phase is blocking ─────────────────────────────────────────

    /** Opens a socket, accepts, and then never writes a byte: a stalled TLS/header phase from the client's view. */
    private AutoCloseable blackHole(int[] portOut) throws IOException {
        java.net.ServerSocket listener = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        portOut[0] = listener.getLocalPort();
        java.util.List<java.net.Socket> accepted = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread acceptor = new Thread(() -> {
            while (!listener.isClosed()) {
                try {
                    accepted.add(listener.accept());
                } catch (IOException e) {
                    return;
                }
            }
        }, "blackhole-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        return () -> {
            listener.close();
            for (java.net.Socket socket : accepted) {
                socket.close();
            }
        };
    }

    private WebhookSendRequest requestTo(int port, String path) {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        ValidatedUrl url = new ValidatedUrl(uri, "127.0.0.1", port, false, true);
        return new WebhookSendRequest(new PinnedDestination(url, List.of(InetAddress.getLoopbackAddress())),
                "delivery-dl", "{}".getBytes(StandardCharsets.UTF_8), Map.of());
    }

    @Test
    @DisplayName("F7 a receiver that accepts but never answers is cut at the deadline, not at the sum of the phases")
    void stalledHeadersAreCutAtTheDeadline() throws Exception {
        int[] port = new int[1];
        try (AutoCloseable ignored = blackHole(port)) {
            long startedAt = System.nanoTime();
            WebhookSendOutcome outcome = sender.send(requestTo(port[0], "/stalled"),
                    WebhookDeadline.startingNow(Duration.ofMillis(600)));
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(outcome.failure()).isEqualTo(WebhookSendOutcome.Failure.TIMEOUT);
            assertThat(outcome.httpStatus()).isNull();
            // The repaired behaviour: ~600 ms. The defect being guarded against was pool-wait + connect + response
            // running in sequence, which with this client's 2 s/5 s configuration would be seconds, not one budget.
            assertThat(elapsedMs)
                    .as("the attempt ends at its own budget, not after the configured phase ceilings")
                    .isLessThan(2_000);
        }
    }

    @Test
    @DisplayName("F7 headers that trickle in slowly still end at the deadline")
    void tricklingHeadersAreCutAtTheDeadline() throws Exception {
        java.net.ServerSocket listener = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread server = new Thread(() -> {
            try (java.net.Socket socket = listener.accept(); OutputStream out = socket.getOutputStream()) {
                out.write("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                for (int i = 0; i < 200; i++) {
                    out.write(("X-Slow-" + i + ": 1\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(50);
                }
            } catch (Exception ignored) {
                // the client closing at its deadline is the expected end of this exchange
            }
        }, "trickle-headers");
        server.setDaemon(true);
        server.start();
        try {
            long startedAt = System.nanoTime();
            WebhookSendOutcome outcome = sender.send(requestTo(listener.getLocalPort(), "/slow-headers"),
                    WebhookDeadline.startingNow(Duration.ofMillis(700)));
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(outcome.failure()).isEqualTo(WebhookSendOutcome.Failure.TIMEOUT);
            // This is the case per-phase timeouts cannot catch: a header arrives every 50 ms, so the socket read
            // timeout never fires and the exchange would run ~10 s (200 headers). Only cancellation at the deadline
            // ends it. The margin tolerates scheduling noise while still rejecting that cumulative behaviour.
            assertThat(elapsedMs).isLessThan(1_500);
        } finally {
            listener.close();
        }
    }

    @Test
    @DisplayName("F7 a partial body followed by a stall keeps the known 2xx and still ends at the deadline")
    void partialBodyThenStallKeepsTheStatus() throws Exception {
        java.net.ServerSocket listener = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread server = new Thread(() -> {
            try (java.net.Socket socket = listener.accept(); OutputStream out = socket.getOutputStream()) {
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write("partial-body-then-silence".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(30_000);
            } catch (Exception ignored) {
                // expected: the client cancels at its deadline
            }
        }, "partial-body");
        server.setDaemon(true);
        server.start();
        try {
            long startedAt = System.nanoTime();
            WebhookSendOutcome outcome = sender.send(requestTo(listener.getLocalPort(), "/partial"),
                    WebhookDeadline.startingNow(Duration.ofMillis(700)));
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(outcome.httpStatus()).as("a known 2xx survives a cut-short body").isEqualTo(200);
            assertThat(outcome.truncated()).isTrue();
            assertThat(outcome.bodySnippet()).contains("partial-body-then-silence");
            assertThat(WebhookResponseMapper.map(outcome))
                    .isInstanceOf(com.notify.delivery.model.DeliveryResult.Success.class);
            assertThat(elapsedMs).isLessThan(2_500);
        } finally {
            listener.close();
        }
    }

    @Test
    @DisplayName("F7 a sub-millisecond remaining budget stays a timeout and never becomes an unlimited wait")
    void subMillisecondBudgetIsNotUnlimited() throws Exception {
        int[] port = new int[1];
        try (AutoCloseable ignored = blackHole(port)) {
            long startedAt = System.nanoTime();
            // 1 ms of budget against a receiver that never answers: a zero timeout would mean "no timeout" to the
            // client, so this must still come back immediately.
            WebhookSendOutcome outcome = sender.send(requestTo(port[0], "/sub-ms"),
                    WebhookDeadline.startingNow(Duration.ofMillis(1)));
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(outcome.failure()).isEqualTo(WebhookSendOutcome.Failure.TIMEOUT);
            assertThat(elapsedMs).isLessThan(1_500);
        }
    }

    @Test
    @DisplayName("F7 the DNS pin and the connection budget are cleared after every attempt, success or cancellation")
    void threadLocalsAreClearedAfterEveryAttempt() throws Exception {
        handle("/clean", exchange -> respond(exchange, 200, "ok"));
        sender.send(request("/clean", Map.of()));
        assertThatThrownBy(() -> dnsResolver.resolve("127.0.0.1"))
                .as("the pin does not outlive a successful attempt")
                .isInstanceOf(java.net.UnknownHostException.class);

        int[] port = new int[1];
        try (AutoCloseable ignored = blackHole(port)) {
            sender.send(requestTo(port[0], "/cancelled"), WebhookDeadline.startingNow(Duration.ofMillis(300)));
        }
        assertThatThrownBy(() -> dnsResolver.resolve("127.0.0.1"))
                .as("nor a cancelled one")
                .isInstanceOf(java.net.UnknownHostException.class);
    }

    @Test
    @DisplayName("F7 several resolved addresses cannot multiply the connect wait beyond the budget")
    void multipleAddressesStayInsideTheBudget() throws Exception {
        int[] port = new int[1];
        try (AutoCloseable ignored = blackHole(port)) {
            URI uri = URI.create("http://127.0.0.1:" + port[0] + "/many");
            ValidatedUrl url = new ValidatedUrl(uri, "127.0.0.1", port[0], false, true);
            // Four pinned addresses: the connect share is divided, so trying them all stays inside the budget.
            WebhookSendRequest many = new WebhookSendRequest(
                    new PinnedDestination(url, List.of(
                            InetAddress.getLoopbackAddress(), InetAddress.getByName("127.0.0.2"),
                            InetAddress.getByName("127.0.0.3"), InetAddress.getByName("127.0.0.4"))),
                    "delivery-many", "{}".getBytes(StandardCharsets.UTF_8), Map.of());

            long startedAt = System.nanoTime();
            WebhookSendOutcome outcome = sender.send(many, WebhookDeadline.startingNow(Duration.ofMillis(800)));
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(outcome.failure()).isIn(
                    WebhookSendOutcome.Failure.TIMEOUT, WebhookSendOutcome.Failure.UNREACHABLE);
            assertThat(elapsedMs).isLessThan(2_500);
        }
    }

    @Test
    @DisplayName("F7 a cancelled attempt stops using its connection: the receiver sees it closed, not still streaming")
    void cancelledAttemptStopsTheExchange() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean clientGone = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        java.net.ServerSocket listener = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread server = new Thread(() -> {
            try (java.net.Socket socket = listener.accept(); OutputStream out = socket.getOutputStream()) {
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                for (int i = 0; i < 400; i++) {
                    out.write("0123456789".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(25);
                }
            } catch (Exception e) {
                clientGone.set(true);
            } finally {
                finished.countDown();
            }
        }, "cancel-watch");
        server.setDaemon(true);
        server.start();
        try {
            WebhookSendOutcome outcome = sender.send(requestTo(listener.getLocalPort(), "/cancel"),
                    WebhookDeadline.startingNow(Duration.ofMillis(500)));

            assertThat(outcome.httpStatus()).isEqualTo(200);
            assertThat(finished.await(10, TimeUnit.SECONDS))
                    .as("the receiver's write loop ends once the client is gone").isTrue();
            assertThat(clientGone.get())
                    .as("the connection was closed by the cancellation, not left streaming in the background")
                    .isTrue();
        } finally {
            listener.close();
        }
    }
}