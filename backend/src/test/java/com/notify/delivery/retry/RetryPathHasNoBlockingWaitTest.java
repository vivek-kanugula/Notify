package com.notify.delivery.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No retry is implemented by sleeping a thread (/ , ).
 *
 * <p>A structural check rather than a behavioural one, because the property is an absence: a retry must be durable
 * in {@code next_retry_at} plus a Quartz intent, never a worker or request thread parked until the next attempt.
 * Behavioural tests can show that the existing paths do not sleep today; only a scan can show that a future one
 * does not reintroduce it, which is the risk worth guarding.
 *
 * <p>Scope is the delivery decision, retry and recovery packages. Deliberately <b>not</b> the whole source tree:
 * bounded waiting is legitimate elsewhere - a watchdog, a bounded body read, a test fixture - and asserting over
 * code where sleeping is correct would make this check noise.
 */
class RetryPathHasNoBlockingWaitTest {

    private static final List<String> SCANNED_PACKAGES = List.of(
            "src/main/java/com/notify/delivery/retry",
            "src/main/java/com/notify/delivery/recovery",
            "src/main/java/com/notify/delivery/service",
            "src/main/java/com/notify/quartz/job",
            "src/main/java/com/notify/quartz/service"
    );

    /** Forms of "park this thread until later" that must not appear on these paths. */
    private static final List<String> FORBIDDEN = List.of(
            "Thread.sleep",
            "TimeUnit.SECONDS.sleep",
            "TimeUnit.MILLISECONDS.sleep",
            "LockSupport.park",
            "Thread.onSpinWait"
    );

    /**
     * The one accepted exemption, named rather than pattern-matched away.
     *
     * <p>{@code TransientDbRetry} is 's accepted bounded in-memory retry for a short <b>database</b> blip between
     * a committed claim and a committed result - four attempts at 50/150/300 ms, only for CONNECTIVITY and
     * CONTENTION, and it never calls the provider again (plan section 6.5). That is a sub-second database
     * reconnection, not a delivery retry parked on a thread, and is about the latter. It is listed here, with
     * its reason, so that the guard stays strict for everything else: any new sleep on these paths still fails, and
     * loosening the pattern would have hidden them all.
     */
    private static final List<String> ACCEPTED_EXEMPTIONS = List.of("TransientDbRetry.java");

    @Test
    @DisplayName("the delivery decision, retry, recovery and scheduling paths never sleep a thread")
    void noBlockingWaitOnTheRetryPath() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<String> exempted = new ArrayList<>();
        int filesScanned = 0;

        for (String packagePath : SCANNED_PACKAGES) {
            Path root = Path.of(packagePath);
            assertThat(Files.isDirectory(root))
                    .as("the scanned package %s must exist, or this check silently passes over nothing", packagePath)
                    .isTrue();
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    filesScanned++;
                    String name = file.getFileName().toString();
                    if (ACCEPTED_EXEMPTIONS.contains(name)) {
                        exempted.add(name);
                        continue;
                    }
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    for (String forbidden : FORBIDDEN) {
                        if (source.contains(forbidden)) {
                            offenders.add(file + " contains " + forbidden);
                        }
                    }
                }
            }
        }

        assertThat(filesScanned).as("the scan actually read sources").isGreaterThan(5);
        assertThat(exempted)
                .as("the exemption list must still match a real file, or it is silently excusing nothing")
                .containsExactlyInAnyOrderElementsOf(ACCEPTED_EXEMPTIONS);
        assertThat(offenders)
                .as("a retry must be durable in next_retry_at plus a Quartz intent, never a parked thread")
                .isEmpty();
    }
}
