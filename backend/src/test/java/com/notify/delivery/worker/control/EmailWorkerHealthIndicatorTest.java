package com.notify.delivery.worker.control;

import com.notify.delivery.worker.config.EmailWorkerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/** worker connected / lag / readiness behavior with explicit thresholds (not part of the readiness group). */
class EmailWorkerHealthIndicatorTest {

    private static final Instant T0 = Instant.parse("2026-10-11T12:00:00Z");

    private static final class MutableClock extends Clock {
        volatile Instant now = T0;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class StubContainer implements WorkerContainerController.ContainerAccess {
        volatile boolean running = true;
        volatile int assigned = 3;
        volatile double lag = Double.NaN;

        @Override
        public void pause(TopicPartition partition) {
        }

        @Override
        public void resume(TopicPartition partition) {
        }

        @Override
        public void stop(Runnable onStopped) {
            running = false;
            onStopped.run();
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public Collection<TopicPartition> assignedPartitions() {
            return java.util.stream.IntStream.range(0, assigned).mapToObj(i -> new TopicPartition("t", i)).toList();
        }

        @Override
        public double maxLag() {
            return lag;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final StubContainer container = new StubContainer();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService stopper = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean databaseUp = true;

    @AfterEach
    void tearDown() {
        timers.shutdownNow();
        stopper.shutdownNow();
    }

    private EmailWorkerProperties props(boolean autoStartup) {
        return props(autoStartup, Duration.ofSeconds(30));
    }

    private EmailWorkerProperties props(boolean autoStartup, Duration pauseInitial) {
        return new EmailWorkerProperties(true, "t", "g", autoStartup, Duration.ofMillis(1), 3,
                pauseInitial, Duration.ofSeconds(300), 2, Duration.ofMillis(20), 1,
                Duration.ofSeconds(1), 1000, Duration.ofSeconds(60));
    }

    private WorkerContainerController controller(EmailWorkerProperties props) {
        return new WorkerContainerController(container, props, () -> databaseUp, new SimpleMeterRegistry(), clock, timers, stopper, false);
    }

    private Health health(WorkerContainerController controller, EmailWorkerProperties props) {
        return new EmailWorkerHealthIndicator(controller, props, clock).health();
    }

    @Test
    @DisplayName("connected, assigned, no lag data yet: UP")
    void upWhenConnected() {
        EmailWorkerProperties props = props(true);
        Health h = health(controller(props), props);
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("assignedPartitions", 3).containsEntry("maxLag", "unknown")
                .containsEntry("lagThreshold", 1000L).containsEntry("containerRunning", true);
    }

    @Test
    @DisplayName("lag at the threshold is UP; lag above it is OUT_OF_SERVICE")
    void lagThreshold() {
        EmailWorkerProperties props = props(true);
        WorkerContainerController c = controller(props);
        container.lag = 1000;
        assertThat(health(c, props).getStatus()).isEqualTo(Status.UP);
        container.lag = 1001;
        Health h = health(c, props);
        assertThat(h.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(h.getDetails()).containsEntry("reason", "consumer lag above threshold").containsEntry("maxLag", 1001L);
    }

    @Test
    @DisplayName("no partitions assigned: UP inside the 60 s grace period, OUT_OF_SERVICE after it")
    void assignmentGrace() {
        EmailWorkerProperties props = props(true);
        WorkerContainerController c = controller(props);
        container.assigned = 0;

        clock.now = T0.plusSeconds(60);
        assertThat(health(c, props).getStatus()).as("exactly at the grace period").isEqualTo(Status.UP);
        clock.now = T0.plusSeconds(61);
        Health h = health(c, props);
        assertThat(h.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(h.getDetails()).containsEntry("reason", "no partitions assigned after the grace period");

        container.assigned = 2;
        assertThat(health(c, props).getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("a partition paused repeatedly (escalation) is OUT_OF_SERVICE; progress clears it")
    void escalationReported() {
        EmailWorkerProperties props = props(true, Duration.ofMillis(40));
        WorkerContainerController c = controller(props);
        TopicPartition p = new TopicPartition("t", 1);

        c.pauseWithBackoff(p);
        Health single = health(c, props);
        assertThat(single.getStatus()).as("one pause is expected behavior").isEqualTo(Status.UP);
        assertThat(single.getDetails().get("pausedPartitions")).isEqualTo(List.of(p.toString()));

        c.pauseWithBackoff(p);   // escalate-after = 2
        Health escalated = health(c, props);
        assertThat(escalated.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(escalated.getDetails().get("escalatedPartitions")).isEqualTo(List.of(p.toString()));

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> c.pausedPartitions().isEmpty());
        c.onProgress(p);   // a record on the resumed partition was processed and acknowledged
        assertThat(health(c, props).getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("a failing worker-pool database probe is OUT_OF_SERVICE")
    void databaseProbeFailing() throws Exception {
        EmailWorkerProperties props = props(true);
        WorkerContainerController c = controller(props);
        databaseUp = false;
        c.pauseUntilHealthy(new TopicPartition("t", 0));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(c.snapshot().databaseHealthy()).isFalse());
        Health h = health(c, props);
        assertThat(h.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(h.getDetails()).containsEntry("reason", "worker database probe failing");
    }

    @Test
    @DisplayName("a requested stop is DOWN with the reason")
    void stoppedIsDown() {
        EmailWorkerProperties props = props(true);
        WorkerContainerController c = controller(props);
        c.stopAsync("NON_TRANSIENT: schema");
        Health h = health(c, props);
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsEntry("reason", "NON_TRANSIENT: schema");
    }

    @Test
    @DisplayName("a container that is not running is DOWN when auto-start is on, UNKNOWN when it is off")
    void notRunning() {
        container.running = false;
        EmailWorkerProperties on = props(true);
        assertThat(health(controller(on), on).getStatus()).isEqualTo(Status.DOWN);
        EmailWorkerProperties off = props(false);
        assertThat(health(controller(off), off).getStatus()).isEqualTo(Status.UNKNOWN);
    }
}
