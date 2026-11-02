package com.notify.delivery.worker.control;

import com.notify.delivery.worker.config.EmailWorkerProperties;

import java.time.Clock;

/**
 * The {@code emailWorker} health component (). All logic and thresholds live in {@link WorkerHealthIndicator}, which
 * shares with the webhook worker; this subclass only binds the email controller and properties, and its bean name
 * is what gives the component its name.
 */
public class EmailWorkerHealthIndicator extends WorkerHealthIndicator {

    public EmailWorkerHealthIndicator(
            WorkerContainerController controller, EmailWorkerProperties props, Clock clock) {
        super(controller, props, clock);
    }
}
