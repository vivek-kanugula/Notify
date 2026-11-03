package com.notify.delivery.provider;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.provider.resend.ResendEmailProvider;
import com.notify.delivery.provider.resend.ResendProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R5, R6, R7: Resend adapter against a scripted local socket server (no network, no credentials). */
class ResendEmailProviderTest {

    private static final String API_KEY = "key-SECRET-TOKEN-777";
    private static final String RECIPIENT = "private.person@example.com";
    private static final String BODY = "BODY-SECRET-PHRASE";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─── scripted server ───────────────────────────────────────────────────────────────────────────────────────────

    record Captured(String method, String path, Map<String, String> headers, String body) {
    }

    interface Handler {
        void handle(Socket socket, Captured request) throws Exception;
    }

    static final class ScriptedServer implements AutoCloseable {
        final ServerSocket server;
        final List<Captured> requests = new CopyOnWriteArrayList<>();
        volatile Handler handler = (socket, request) -> respond(socket, 200, "{\"id\":\"re_default\"}");

        ScriptedServer() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        Thread.ofVirtual().start(() -> serve(socket));
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        private void serve(Socket socket) {
            try (socket) {
                InputStream in = socket.getInputStream();
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                int state = 0;
                while (state < 4) {
                    int b = in.read();
                    if (b < 0) {
                        return;
                    }
                    head.write(b);
                    state = (b == '\r' && (state == 0 || state == 2)) ? state + 1
                            : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : (b == '\r' ? 1 : 0);
                }
                String[] lines = head.toString(StandardCharsets.ISO_8859_1).split("\r\n");
                String[] requestLine = lines[0].split(" ");
                Map<String, String> headers = new HashMap<>();
                for (int i = 1; i < lines.length; i++) {
                    int colon = lines[i].indexOf(':');
                    if (colon > 0) {
                        headers.put(lines[i].substring(0, colon).trim().toLowerCase(), lines[i].substring(colon + 1).trim());
                    }
                }
                int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                byte[] body = in.readNBytes(length);
                Captured captured = new Captured(requestLine[0], requestLine[1], headers, new String(body, StandardCharsets.UTF_8));
                requests.add(captured);
                handler.handle(socket, captured);
            } catch (Exception ignored) {
                // client aborted, scenario ended the connection, or the server is closing
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    static void respond(Socket socket, int status, String body) throws IOException {
        respond(socket, status, body, Map.of());
    }

    static void respond(Socket socket, int status, String body, Map<String, String> extraHeaders) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(" X\r\n")
                .append("Content-Type: application/json\r\nContent-Length: ").append(payload.length).append("\r\nConnection: close\r\n");
        extraHeaders.forEach((k, v) -> head.append(k).append(": ").append(v).append("\r\n"));
        head.append("\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(payload);
        out.flush();
    }

    // ─── fixtures ──────────────────────────────────────────────────────────────────────────────────────────────────

    private ScriptedServer server;
    private ResendEmailProvider provider;

    private static EmailSendRequest request() {
        return new EmailSendRequest("nid-1-1", "Notify <notifications@example.com>", RECIPIENT, "Hello é", BODY);
    }

    private ResendEmailProvider provider(Duration connect, Duration read, int maxBytes) {
        return new ResendEmailProvider(new ResendProperties(API_KEY, server.baseUrl(), connect, read, maxBytes));
    }

    @BeforeEach
    void setUp() throws IOException {
        server = new ScriptedServer();
        provider = provider(Duration.ofSeconds(2), Duration.ofSeconds(8), 65_536);
    }

    @AfterEach
    void tearDown() throws IOException {
        provider.shutdown();
        server.close();
    }

    // ─── tests ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R5/R6 the request is POST /emails with bearer key, idempotency key and the exact JSON shape")
    void requestShape() throws Exception {
        server.handler = (socket, req) -> respond(socket, 200, "{\"id\":\"re_123\"}");

        DeliveryResult result = provider.send(request());

        assertThat(result).isEqualTo(new DeliveryResult.Success("re_123"));
        assertThat(server.requests).hasSize(1);
        Captured captured = server.requests.get(0);
        assertThat(captured.method()).isEqualTo("POST");
        assertThat(captured.path()).isEqualTo("/emails");
        assertThat(captured.headers().get("authorization")).isEqualTo("Bearer " + API_KEY);
        assertThat(captured.headers().get("idempotency-key")).isEqualTo("nid-1-1");
        assertThat(captured.headers().get("content-type")).startsWith("application/json");
        JsonNode json = MAPPER.readTree(captured.body());
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("from", "to", "subject", "text");
        assertThat(json.get("from").asText()).isEqualTo("Notify <notifications@example.com>");
        assertThat(json.get("to")).hasSize(1);
        assertThat(json.get("to").get(0).asText()).isEqualTo(RECIPIENT);
        assertThat(json.get("subject").asText()).isEqualTo("Hello é");
        assertThat(json.get("text").asText()).isEqualTo(BODY);
    }

    @Test
    @DisplayName("R5/R6 request includes reply_to when provided in EmailSendRequest")
    void requestWithReplyTo() throws Exception {
        server.handler = (socket, req) -> respond(socket, 200, "{\"id\":\"re_replyto_123\"}");

        EmailSendRequest req = new EmailSendRequest(
                "nid-1-2",
                "\"Acme Corp via Notify\" <notifications@example.com>",
                RECIPIENT,
                "Support Notification",
                BODY,
                "support@acme.com"
        );

        DeliveryResult result = provider.send(req);

        assertThat(result).isEqualTo(new DeliveryResult.Success("re_replyto_123"));
        assertThat(server.requests).hasSize(1);
        Captured captured = server.requests.get(0);
        JsonNode json = MAPPER.readTree(captured.body());
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("from", "to", "subject", "text", "reply_to");
        assertThat(json.get("from").asText()).isEqualTo("\"Acme Corp via Notify\" <notifications@example.com>");
        assertThat(json.get("reply_to").asText()).isEqualTo("support@acme.com");
    }

    @Test
    @DisplayName("R1/R5 end-to-end status mapping over a real HTTP exchange")
    void statusMappingEndToEnd() {
        Object[][] cases = {
                {401, "{\"name\":\"missing_api_key\",\"message\":\"x\"}", "AUTHENTICATION_FAILED", false},
                {422, "{\"name\":\"validation_error\",\"message\":\"x\"}", "VALIDATION_FAILED", false},
                {429, "{\"name\":\"rate_limit_exceeded\",\"message\":\"x\"}", "RATE_LIMITED", true},
                {500, "{\"name\":\"application_error\",\"message\":\"x\"}", "PROVIDER_UNAVAILABLE", true},
                {409, "{\"name\":\"concurrent_idempotent_requests\",\"message\":\"x\"}", "CONCURRENT_IDEMPOTENCY_IN_PROGRESS", true},
                {409, "{\"name\":\"invalid_idempotent_request\",\"message\":\"x\"}", "IDEMPOTENCY_PAYLOAD_MISMATCH", false},
        };
        for (Object[] c : cases) {
            server.handler = (socket, req) -> respond(socket, (Integer) c[0], (String) c[1]);
            DeliveryResult result = provider.send(request());
            if ((Boolean) c[3]) {
                assertThat(result).as(String.valueOf(c[0])).isInstanceOfSatisfying(DeliveryResult.RetryableFailure.class,
                        r -> assertThat(r.code()).isEqualTo(c[2]));
            } else {
                assertThat(result).as(String.valueOf(c[0])).isInstanceOfSatisfying(DeliveryResult.PermanentFailure.class,
                        r -> assertThat(r.code()).isEqualTo(c[2]));
            }
        }
    }

    @Test
    @DisplayName("R5 delayed response headers hit the read timeout: retryable PROVIDER_TIMEOUT well before the delay ends")
    void delayedHeaders() throws Exception {
        server.handler = (socket, req) -> {
            Thread.sleep(2_500);
            respond(socket, 200, "{\"id\":\"late\"}");
        };
        ResendEmailProvider fast = provider(Duration.ofMillis(500), Duration.ofMillis(300), 65_536);
        try {
            long begin = System.nanoTime();
            DeliveryResult result = fast.send(request());
            long elapsedMs = Duration.ofNanos(System.nanoTime() - begin).toMillis();

            assertThat(result).isInstanceOfSatisfying(DeliveryResult.RetryableFailure.class, r -> assertThat(r.code()).isEqualTo("PROVIDER_TIMEOUT"));
            assertThat(elapsedMs).isLessThan(1_800);
        } finally {
            fast.shutdown();
        }
    }

    @Test
    @DisplayName("R5 a stalled 2xx body is cut off by the total budget: retryable PROVIDER_TIMEOUT")
    void stalledBody() throws Exception {
        server.handler = (socket, req) -> {
            OutputStream out = socket.getOutputStream();
            out.write("HTTP/1.1 200 X\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n0123456789".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            Thread.sleep(4_000);
        };
        ResendEmailProvider fast = provider(Duration.ofMillis(400), Duration.ofMillis(400), 65_536);
        try {
            long begin = System.nanoTime();
            DeliveryResult result = fast.send(request());
            long elapsedMs = Duration.ofNanos(System.nanoTime() - begin).toMillis();

            assertThat(result).isInstanceOfSatisfying(DeliveryResult.RetryableFailure.class, r -> assertThat(r.code()).isEqualTo("PROVIDER_TIMEOUT"));
            assertThat(elapsedMs).isLessThan(2_500);
        } finally {
            fast.shutdown();
        }
    }

    @Test
    @DisplayName("R5 a trickling body (one byte per 100 ms) cannot outlast the total budget")
    void tricklingBody() throws Exception {
        server.handler = (socket, req) -> {
            OutputStream out = socket.getOutputStream();
            out.write("HTTP/1.1 200 X\r\nContent-Type: application/json\r\nContent-Length: 400\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            for (int i = 0; i < 400; i++) {
                out.write('x');
                out.flush();
                Thread.sleep(100);
            }
        };
        ResendEmailProvider fast = provider(Duration.ofMillis(400), Duration.ofMillis(400), 65_536);
        try {
            long begin = System.nanoTime();
            DeliveryResult result = fast.send(request());
            long elapsedMs = Duration.ofNanos(System.nanoTime() - begin).toMillis();

            assertThat(result).isInstanceOfSatisfying(DeliveryResult.RetryableFailure.class, r -> assertThat(r.code()).isEqualTo("PROVIDER_TIMEOUT"));
            assertThat(elapsedMs).isLessThan(2_500);
        } finally {
            fast.shutdown();
        }
    }

    @Test
    @DisplayName("R5 a stalled non-2xx body is still classified by its status")
    void stalledErrorBodyUsesStatus() throws Exception {
        server.handler = (socket, req) -> {
            OutputStream out = socket.getOutputStream();
            out.write("HTTP/1.1 503 X\r\nContent-Length: 100\r\n\r\nabc".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            Thread.sleep(4_000);
        };
        ResendEmailProvider fast = provider(Duration.ofMillis(400), Duration.ofMillis(400), 65_536);
        try {
            assertThat(fast.send(request())).isInstanceOfSatisfying(DeliveryResult.RetryableFailure.class,
                    r -> assertThat(r.code()).isEqualTo("PROVIDER_UNAVAILABLE"));
        } finally {
            fast.shutdown();
        }
    }

    @Test
    @DisplayName("R5 an oversized 2xx body is retryable INVALID_PROVIDER_RESPONSE and is not buffered unboundedly")
    void oversizedSuccessBody() throws Exception {
        server.handler = (socket, req) -> respond(socket, 200, "{\"id\":\"re_1\",\"pad\":\"" + "p".repeat(50_000) + "\"}");
        ResendEmailProvider small = provider(Duration.ofSeconds(2), Duration.ofSeconds(8), 1_024);
        try {
            assertThat(small.send(request())).isInstanceOfSatisfying(DeliveryResult.RetryableFailure.class,
                    r -> assertThat(r.code()).isEqualTo("INVALID_PROVIDER_RESPONSE"));
        } finally {
            small.shutdown();
        }
    }

    @Test
    @DisplayName("R5 an oversized non-2xx body is classified by status")
    void oversizedErrorBody() throws Exception {
        server.handler = (socket, req) -> respond(socket, 503, "e".repeat(50_000));
        ResendEmailProvider small = provider(Duration.ofSeconds(2), Duration.ofSeconds(8), 1_024);
        try {
            assertThat(small.send(request())).isInstanceOfSatisfying(DeliveryResult.RetryableFailure.class,
                    r -> assertThat(r.code()).isEqualTo("PROVIDER_UNAVAILABLE"));
        } finally {
            small.shutdown();
        }
    }

    @Test
    @DisplayName("R5 connection refused is retryable PROVIDER_UNAVAILABLE")
    void connectionRefused() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        ResendEmailProvider refused = new ResendEmailProvider(new ResendProperties(
                API_KEY, "http://127.0.0.1:" + closedPort, Duration.ofSeconds(2), Duration.ofSeconds(8), 65_536));
        try {
            assertThat(refused.send(request())).isInstanceOfSatisfying(DeliveryResult.RetryableFailure.class,
                    r -> assertThat(r.code()).isEqualTo("PROVIDER_UNAVAILABLE"));
        } finally {
            refused.shutdown();
        }
    }

    @Test
    @DisplayName("R5 a connection closed before any response is retryable PROVIDER_UNAVAILABLE")
    void connectionClosedWithoutResponse() {
        server.handler = (socket, req) -> socket.close();
        assertThat(provider.send(request())).isInstanceOfSatisfying(DeliveryResult.RetryableFailure.class,
                r -> assertThat(r.code()).isEqualTo("PROVIDER_UNAVAILABLE"));
    }

    @Test
    @DisplayName("R5 redirects are never followed (the key and body must not travel elsewhere)")
    void redirectsAreNotFollowed() {
        server.handler = (socket, req) -> respond(socket, 302, "{}", Map.of("Location", server.baseUrl() + "/elsewhere"));
        assertThat(provider.send(request())).isInstanceOfSatisfying(DeliveryResult.PermanentFailure.class,
                r -> assertThat(r.code()).isEqualTo("PROVIDER_CLIENT_ERROR"));
        assertThat(server.requests).hasSize(1);
    }

    @Test
    @DisplayName("R6 retrying the same request sends the same idempotency key and sender")
    void identityStableAcrossRetries() throws Exception {
        server.handler = (socket, req) -> {
            if (server.requests.size() == 1) {
                respond(socket, 503, "{}");
            } else {
                respond(socket, 200, "{\"id\":\"re_2\"}");
            }
        };
        assertThat(provider.send(request())).isInstanceOf(DeliveryResult.RetryableFailure.class);
        assertThat(provider.send(request())).isEqualTo(new DeliveryResult.Success("re_2"));

        assertThat(server.requests).hasSize(2);
        assertThat(server.requests.get(0).headers().get("idempotency-key")).isEqualTo("nid-1-1");
        assertThat(server.requests.get(1).headers().get("idempotency-key")).isEqualTo("nid-1-1");
        assertThat(MAPPER.readTree(server.requests.get(0).body()).get("from").asText())
                .isEqualTo(MAPPER.readTree(server.requests.get(1).body()).get("from").asText());
    }

    @Test
    @DisplayName("R7 the API key, Authorization header, recipient and body are never logged; returned error text has addresses masked")
    void secretsNeverLoggedOrReturned() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previous = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.DEBUG);
        try {
            server.handler = (socket, req) -> respond(socket, 422,
                    "{\"name\":\"validation_error\",\"message\":\"The to address " + RECIPIENT + " is invalid\"}");
            DeliveryResult echoed = provider.send(request());
            server.handler = (socket, req) -> respond(socket, 200, "{\"id\":\"re_ok\"}");
            provider.send(request());
            server.handler = (socket, req) -> socket.close();
            provider.send(request());

            DeliveryResult.PermanentFailure failure = (DeliveryResult.PermanentFailure) echoed;
            assertThat(failure.message()).doesNotContain(RECIPIENT).doesNotContain("private.person");
            assertThat(failure.snippet()).doesNotContain(RECIPIENT).doesNotContain("private.person");

            for (ILoggingEvent event : List.copyOf(appender.list)) {
                String text = event.getFormattedMessage() + " " + render(event.getThrowableProxy());
                assertThat(text).as(event.getLoggerName()).doesNotContain(API_KEY).doesNotContain("SECRET-TOKEN")
                        .doesNotContain(RECIPIENT).doesNotContain("private.person").doesNotContain(BODY);
            }
        } finally {
            root.detachAppender(appender);
            root.setLevel(previous);
        }
    }

    private static String render(IThrowableProxy proxy) {
        StringBuilder sb = new StringBuilder();
        for (IThrowableProxy p = proxy; p != null; p = p.getCause()) {
            sb.append(p.getClassName()).append(' ').append(p.getMessage()).append(' ');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("R8 the adapter refuses to start without an API key")
    void requiresApiKey() {
        assertThatThrownBy(() -> new ResendEmailProvider(new ResendProperties("", "http://127.0.0.1:1", Duration.ofSeconds(2), Duration.ofSeconds(8), 65_536)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("notify.resend.api-key");
        assertThatThrownBy(() -> new ResendEmailProvider(new ResendProperties("   ", "http://127.0.0.1:1", Duration.ofSeconds(2), Duration.ofSeconds(8), 65_536)))
                .isInstanceOf(IllegalStateException.class);
    }
}
