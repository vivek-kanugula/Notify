package com.notify.delivery.worker.control;

import com.notify.delivery.worker.config.WorkerTuning;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import java.time.Clock;
import java.time.Duration;

/**
 * Health of one channel delivery worker (), reported as the {@code <channel>Worker} component of
 * {@code /actuator/health}. Deliberately <em>not</em> part of the readiness group, so established HTTP readiness
 * ({@code readinessState,db}) is unchanged: a stalled worker must not take the ingress API out of rotation.
 *
 * <p>Explicit thresholds, first match wins:
 * <ol>
 *   <li>stop requested, or container not running while auto-start is on: {@code DOWN}</li>
 *   <li>container not running with auto-start off: {@code UNKNOWN}</li>
 *   <li>no partitions assigned for longer than {@code assignment-grace} (default 60 s): {@code OUT_OF_SERVICE}</li>
 *   <li>any partition escalated ({@code escalate-after} consecutive pauses, default 3): {@code OUT_OF_SERVICE}</li>
 *   <li>consumer lag above {@code lag-threshold} records (default 1000): {@code OUT_OF_SERVICE}</li>
 *   <li>worker-pool database probe failing: {@code OUT_OF_SERVICE}</li>
 *   <li>otherwise {@code UP} (paused partitions are reported as details; a short pause is expected behavior)</li>
 * </ol>
 *
 * <p>generalizes the email indicator so both channels report identical thresholds; the per-channel classes are
 * thin subclasses that only supply their own controller and properties.
 */
public class WorkerHealthIndicator implements HealthIndicator {

    private final WorkerContainerController controller;
    private final WorkerTuning props;
    private final Clock clock;

    public WorkerHealthIndicator(WorkerContainerController controller, WorkerTuning props, Clock clock) {
        this.controller = controller;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public Health health() {
        WorkerContainerController.Snapshot s = controller.snapshot();

        Health.Builder builder;
        if (s.stopped()) {
            builder = Health.down().withDetail("reason", s.stopReason() == null ? "stopped" : s.stopReason());
        } else if (!s.containerRunning()) {
            builder = props.autoStartup()
                    ? Health.down().withDetail("reason", "listener container is not running")
                    : Health.status(Status.UNKNOWN).withDetail("reason", "auto-startup is disabled");
        } else if (s.assignedPartitions() == 0
                && Duration.between(s.startedAt(), clock.instant()).compareTo(props.assignmentGrace()) > 0) {
            builder = Health.outOfService().withDetail("reason", "no partitions assigned after the grace period");
        } else if (!s.escalatedPartitions().isEmpty()) {
            builder = Health.outOfService().withDetail("reason", "partition paused repeatedly without progress");
        } else if (!Double.isNaN(s.maxLag()) && s.maxLag() > props.lagThreshold()) {
            builder = Health.outOfService().withDetail("reason", "consumer lag above threshold");
        } else if (!s.databaseHealthy()) {
            builder = Health.outOfService().withDetail("reason", "worker database probe failing");
        } else {
            builder = Health.up();
        }

        return builder
                .withDetail("channel", props.name())
                .withDetail("containerRunning", s.containerRunning())
                .withDetail("assignedPartitions", s.assignedPartitions())
                .withDetail("pausedPartitions", s.pausedPartitions())
                .withDetail("escalatedPartitions", s.escalatedPartitions())
                .withDetail("maxLag", Double.isNaN(s.maxLag()) ? "unknown" : Math.round(s.maxLag()))
                .withDetail("lagThreshold", props.lagThreshold())
                .withDetail("databaseHealthy", s.databaseHealthy())
                .build();
    }
}
