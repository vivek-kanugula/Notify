package com.notify.delivery.worker.control;

import com.notify.delivery.worker.config.InAppWorkerProperties;

import java.time.Clock;

/**
 * The {@code inappWorker} health component (, same thresholds as the other channel workers). Outside the
 * readiness group: a paused or stopped in-app worker must never take the ingress API or another channel out of
 * rotation.
 */
public class InAppWorkerHealthIndicator extends WorkerHealthIndicator {

    public InAppWorkerHealthIndicator(
            WorkerContainerController controller, InAppWorkerProperties props, Clock clock) {
        super(controller, props, clock);
    }
}