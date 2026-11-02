package com.notify.delivery.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link StuckProcessingReaper} on a fixed delay (/ , ).
 *
 * <p>Deliberately Spring's {@code @Scheduled} with {@code @ConditionalOnProperty}, not a Quartz job: this is a
 * periodic scan with no per-notification trigger to persist, and {@code NotificationReconcilerService} already
 * establishes exactly this pattern in this project for exactly this kind of work. Quartz earns its keep for durable
 * per-notification intents - the retry release the reaper hands recovered work back to - and would add a trigger,
 * a job-data map and a scheduler round trip here for nothing. The plan proposed a Quartz job; this is the smaller
 * reuse of what already exists.
 *
 * <p>Separate from the reaper so the reaper stays a plain object a unit test can drive by hand. Both are
 * belt-and-braces gated: the bean does not exist unless {@code notify.recovery.enabled=true}, and
 * {@link StuckProcessingReaper#scan()} checks the same flag again.
 */
@Component
@ConditionalOnProperty(name = "notify.recovery.enabled", havingValue = "true")
public class StuckProcessingReaperScheduler {

    private static final Logger log = LoggerFactory.getLogger(StuckProcessingReaperScheduler.class);

    private final StuckProcessingReaper reaper;

    public StuckProcessingReaperScheduler(StuckProcessingReaper reaper, ReaperProperties props) {
        this.reaper = reaper;
        log.info("Stuck-work recovery is ENABLED: scanning every {} for claims older than {}, at most {} rows a scan",
                props.scanInterval(), props.stuckAfter(), props.maxRowsPerScan());
    }

    @Scheduled(fixedDelayString = "${notify.recovery.scan-interval:60s}")
    public void scan() {
        try {
            reaper.scan();
        } catch (RuntimeException e) {
            // A scheduled method that throws is simply retried at the next delay; log it rather than letting the
            // scheduler swallow it silently.
            log.warn("Stuck-work recovery scan failed ({})", e.getClass().getName());
        }
    }
}
