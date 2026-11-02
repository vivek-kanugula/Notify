package com.notify.delivery.provider.resend;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.provider.EmailSendRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The explicit bound on outstanding response-body readers (/ , 's accepted resource requirement;
 * repair of review finding F3).
 *
 * <p><b>What requires and why a simpler test cannot show it.</b> "Cancelling a reader or reaching the caller's
 * deadline does not prove the underlying task terminated", so the implementation must bound outstanding readers
 * and <b>verify that repeated timeouts cannot accumulate work beyond that bound</b>. A reader blocked in a socket
 * read is precisely the case where {@code future.cancel(true)} and {@code close()} may both fail to end the task,
 * and no HTTP fixture can be relied on to produce one. So the readers here are controlled: their {@code read()}
 * waits on a latch, ignores interruption, and their {@code close()} does nothing. Only the fixture releases them,
 * in a {@code finally}.
 *
 * <p>Everything on the production side is production: the same {@code readBounded}, the same fixed pool of
 * {@link ResendEmailProvider#READER_THREADS} threads with a {@code SynchronousQueue} and {@code AbortPolicy}, the
 * same cancel-and-close on expiry, the same timeout result.
 */
class ResendBodyReaderBoundTest {

    private static final String API_KEY = "key-TEST-ONLY-000";

    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger readersStarted = new AtomicInteger();
    private final AtomicInteger readersFinished = new AtomicInteger();
    private final List<AutoCloseable> fixtures = new ArrayList<>();

    @AfterEach
    void releaseEverything() {
        release.countDown();
        for (AutoCloseable fixture : fixtures) {
            try {
                fixture.close();
            } catch (Exception ignored) {
                // best effort teardown
            }
        }
    }

    /**
     * A stream whose read blocks until the fixture releases it, whatever happens to the task.
     *
     * <p>Interruption is swallowed and {@code close()} is a no-op on purpose: that is what makes this a model of a
     * reader that outlives its cancellation, which is the thing the bound has to survive.
     */
    private final class UninterruptibleStream extends InputStream {

        @Override
        public int read() {
            readersStarted.incrementAndGet();
            boolean released = false;
            while (!released) {
                try {
                    released = release.await(30, TimeUnit.SECONDS);
                    if (!released) {
                        break;                        // hard ceiling, so a broken fixture cannot hang the suite
                    }
                } catch (InterruptedException e) {
                    // Deliberately NOT honoured, and the flag is deliberately not restored while waiting: a socket
                    // read does not end because someone cancelled the future.
                }
            }
            readersFinished.incrementAndGet();
            return -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return read();
        }

        @Override
        public void close() {
            // A no-op: closing the stream must not be what rescues this test, because closing a stalled socket is
            // exactly the thing that may not take effect promptly.
        }
    }

    /** Captures the provider's own log, so the production rejection branch is observed rather than inferred. */
    private ListAppender<ILoggingEvent> captureProviderLogs() {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(ResendEmailProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        fixtures.add(() -> logger.detachAppender(appender));
        return appender;
    }

    private ResendEmailProvider provider(String baseUrl) {
        return new ResendEmailProvider(new ResendProperties(
                API_KEY, baseUrl, Duration.ofMillis(300), Duration.ofMillis(300), 65_536));
    }

    @Test
    @DisplayName("repeated timeouts never accumulate readers beyond the bound, and no backlog is queued")
    void repeatedTimeoutsStayInsideTheBound() {
        ResendEmailProvider provider = provider("http://127.0.0.1:1");
        ListAppender<ILoggingEvent> logs = captureProviderLogs();

        // Fill every slot. Each call gives its reader long enough to actually start, then times out, cancels it and
        // closes the stream - and the reader stays occupied regardless, which is the premise of the whole bound.
        for (int i = 0; i < ResendEmailProvider.READER_THREADS; i++) {
            ResendEmailProvider.BoundedBody body = provider.readBounded(
                    new UninterruptibleStream(), System.nanoTime() + Duration.ofMillis(250).toNanos());
            assertThat(body.timedOut()).as("call %s", i).isTrue();
            awaitReaders(i + 1);
        }

        // Now drive many more submissions. Every one must be refused promptly and must start no reader.
        long startedAt = System.nanoTime();
        for (int i = 0; i < 25; i++) {
            ResendEmailProvider.BoundedBody body = provider.readBounded(
                    new UninterruptibleStream(), System.nanoTime() + Duration.ofMillis(250).toNanos());
            assertThat(body.timedOut())
                    .as("a saturated submission reports the established timeout result, call %s", i)
                    .isTrue();
            assertThat(body.text()).isNull();
            assertThat(body.oversize()).isFalse();
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(readersStarted.get())
                .as("outstanding readers never exceed the pool: a refused submission runs no task")
                .isEqualTo(ResendEmailProvider.READER_THREADS);
        assertThat(logs.list.stream().filter(event -> event.getFormattedMessage().contains("saturated")).count())
                .as("every one of those 25 took the production rejection branch, not a queue")
                .isEqualTo(25);
        assertThat(elapsedMs)
                .as("25 saturated submissions are refused immediately, not queued behind a stalled reader")
                .isLessThan(5_000);

        // And nothing was waiting to run: after the readers are released, no further task appears.
        release.countDown();
        await(() -> readersFinished.get() == ResendEmailProvider.READER_THREADS);
        assertThat(readersStarted.get())
                .as("no backlog accumulated while the pool was saturated")
                .isEqualTo(ResendEmailProvider.READER_THREADS);
    }

    /**
     * Submissions whose budget is <b>already spent</b> still cannot accumulate work beyond the pool.
     *
     * <p>Two outcomes are both legitimate here and the test deliberately accepts either: the worker thread may pick
     * the task up before {@code cancel} lands, in which case the reader starts and occupies a slot, or
     * {@code CompletableFuture.cancel} may complete the future first, in which case {@code AsyncSupply} declines to
     * call the supplier at all and the task evaporates. Which one happens is a race and must not be asserted. What
     * must hold either way is the bound: twenty expired submissions never leave more than
     * {@link ResendEmailProvider#READER_THREADS} readers behind, and the surplus is refused rather than queued.
     */
    @Test
    @DisplayName("submissions on an already-spent budget stay inside the same bound")
    void expiredSubmissionsStayInsideTheBound() {
        ResendEmailProvider provider = provider("http://127.0.0.1:1");
        long alreadyExpired = System.nanoTime() - Duration.ofSeconds(1).toNanos();

        for (int i = 0; i < 20; i++) {
            assertThat(provider.readBounded(new UninterruptibleStream(), alreadyExpired).timedOut()).isTrue();
        }

        assertThat(readersStarted.get())
                .as("the queue-free pool is the bound, whichever side of the cancellation race each task fell on")
                .isLessThanOrEqualTo(ResendEmailProvider.READER_THREADS);
    }

    /**
     * Saturation happens <b>after</b> the request was sent, so it must stay a provider outcome with an unknown
     * remote effect - never the attempt-free local refusal, which is reserved for a decision taken before any
     * provider contact. This drives the real {@code send} against a local server that answers {@code 202} and then
     * stalls its body.
     */
    @Test
    @DisplayName("a stalled body after a sent request is a retryable timeout, never an attempt-free refusal")
    void saturationAfterASentRequestIsNotAGuardRefusal() throws IOException {
        StallingServer server = new StallingServer();
        fixtures.add(server);
        ResendEmailProvider provider = provider(server.baseUrl());

        DeliveryResult result = provider.send(new EmailSendRequest(
                "nid-1-1", "Notify <notifications@example.com>", "someone@example.com", "subject", "body"));

        assertThat(result)
                .as("a sent request whose body never arrives is a provider outcome, not a local refusal")
                .isInstanceOf(DeliveryResult.RetryableFailure.class);
        DeliveryResult.RetryableFailure failure = (DeliveryResult.RetryableFailure) result;
        assertThat(failure.code()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(failure.retryAfterSeconds())
                .as("no receiver asked for anything: the backoff policy alone schedules the retry")
                .isNull();
        assertThat(result).isNotInstanceOf(DeliveryResult.Deferred.class);
        assertThat(server.requests.get()).as("the request really was sent").isEqualTo(1);
    }

    private void awaitReaders(int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (readersStarted.get() == expected) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("readersStarted=" + readersStarted.get() + " expected=" + expected
                + " finished=" + readersFinished.get());
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("condition was not met inside its bounded wait");
    }

    /** Answers a 202 with a Content-Length it never satisfies, so the body read stalls while the status is known. */
    private static final class StallingServer implements AutoCloseable {

        private final ServerSocket socket;
        private final AtomicInteger requests = new AtomicInteger();

        StallingServer() throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread accept = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket client = socket.accept()) {
                        requests.incrementAndGet();
                        client.getInputStream().read(new byte[8192]);
                        OutputStream out = client.getOutputStream();
                        out.write(("HTTP/1.1 202 Accepted\r\n"
                                + "Content-Type: application/json\r\n"
                                + "Content-Length: 4096\r\n\r\n"
                                + "{\"id\":\"re_partial\"").getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                        // Then nothing: the client waits for the rest until its own budget expires.
                        Thread.sleep(5_000);
                    } catch (IOException | InterruptedException e) {
                        return;
                    }
                }
            }, "stalling-resend");
            accept.setDaemon(true);
            accept.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
