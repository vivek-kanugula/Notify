package com.notify.delivery.recovery;

import com.notify.notification.dto.StuckClaimCandidate;
import com.notify.notification.model.NotificationTransitioner.ReapOutcome;
import com.notify.notification.service.NotificationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Recovers notifications stuck in PROCESSING because the worker that claimed them died (/ , ).
 *
 * <p><b>The gap this closes.</b> A claim moves a notification to PROCESSING and increments its {@code attempt_count}
 * and {@code claim_seq}. If the worker then dies - process kill, host loss, an unrecoverable container stop - the
 * row stays PROCESSING for ever, because nothing else scans for it. No retry fires, no dead letter is written, and
 * the notification is simply lost. That is the specific defect that blocks production release.
 *
 * <p><b>How it recovers, and what it reuses.</b> The scan is read-only and keyset-paged, like the existing overdue
 * finders. Each candidate is then reaped by {@code NotificationTransitioner.reapStuckProcessing}, whose two
 * conditional updates re-test every predicate - including the attempt budget - so the decision is atomic with the
 * transition:
 * <ul>
 *   <li><b>budget remaining</b> → RETRYING, due now. From there the <em>existing</em> machinery takes over
 *       unchanged: {@code RetryDueNotificationJob} releases it with {@code releaseRetryConditional}, which performs
 *       the {@code dispatch_generation} bump and publishes a fresh dispatch event;</li>
 *   <li><b>budget spent</b> → FAILED plus one durable dead-letter event with the existing
 *       {@code DlqReason.MAX_RETRIES_EXCEEDED}, written in the same transaction. Without this path, moving a spent
 *       claim to RETRYING would let the due path claim it again and send past {@code max_attempts}.</li>
 * </ul>
 *
 * <p><b>What it deliberately does not do.</b> It does not refund the attempt the lost claim consumed (): that
 * claim may have reached the provider, and refunding would let an endlessly crashing worker send for ever. It does
 * not bump {@code dispatch_generation} (): moving the row out of PROCESSING already fences the original worker,
 * because every result write requires {@code status = PROCESSING} with a matching generation and {@code claim_seq}.
 * It writes no {@code delivery_attempts} row on either path, because no provider outcome was ever observed and
 * {@code AttemptOutcome} has no value for "unknown" - recording one would fabricate a verdict. And it starts no
 * delivery itself: it holds no provider connection, no emitter and no lock, and performs only bounded database work.
 */
@Component
public class StuckProcessingReaper {

    private static final Logger log = LoggerFactory.getLogger(StuckProcessingReaper.class);

    /** The one new {@code last_error_code} value this phase introduces; fits the existing VARCHAR(64). */
    public static final String WORKER_LOST = "WORKER_LOST";

    /**
     * The notification feature's service is the <b>only</b> way in: this class never touches that feature's
     * repository or entities, per the project rule that features call each other through service interfaces.
     */
    private final NotificationService notificationService;
    private final ReaperProperties props;
    private final Clock clock;
    private final MeterRegistry meters;

    public StuckProcessingReaper(
            NotificationService notificationService,
            ReaperProperties props,
            Clock clock,
            ObjectProvider<MeterRegistry> meters
    ) {
        this.notificationService = notificationService;
        this.props = props;
        this.clock = clock;
        this.meters = meters.getIfAvailable(SimpleMeterRegistry::new);
    }

    /** What one scan did. */
    public record ScanReport(int examined, int rescheduled, int exhausted, int skipped) {

        public int reaped() {
            return rescheduled + exhausted;
        }
    }

    /**
     * One bounded scan. Safe to run concurrently with another instance's scan: the conditional updates carry
     * {@code claim_seq}, so for any given claim exactly one reaper wins and the loser updates zero rows.
     */
    public ScanReport scan() {
        if (!props.enabled()) {
            return new ScanReport(0, 0, 0, 0);
        }
        Instant cutoff = clock.instant().minus(props.stuckAfter());
        int examined = 0;
        int rescheduled = 0;
        int exhausted = 0;
        int skipped = 0;

        Instant cursorAt = null;
        UUID cursorId = null;
        for (int page = 0; page < props.maxPagesPerScan(); page++) {
            List<StuckClaimCandidate> batch = notificationService.findStuckProcessingCandidates(
                    cutoff, cursorAt, cursorId, props.batchSize());
            if (batch.isEmpty()) {
                break;
            }
            for (StuckClaimCandidate candidate : batch) {
                examined++;
                ReapOutcome outcome = reapOne(candidate, cutoff);
                switch (outcome) {
                    case RESCHEDULED -> rescheduled++;
                    case EXHAUSTED -> exhausted++;
                    case NOT_STUCK -> skipped++;
                }
            }
            StuckClaimCandidate last = batch.get(batch.size() - 1);
            cursorAt = last.processingStartedAt();
            cursorId = last.id();
            if (batch.size() < props.batchSize()) {
                break;
            }
        }

        if (rescheduled > 0 || exhausted > 0) {
            log.info("Stuck-work recovery: examined {}, rescheduled {}, exhausted {}, skipped {} (cutoff {})",
                    examined, rescheduled, exhausted, skipped, cutoff);
        } else {
            // Every tick leaves a trace, so "the scan found nothing" and "the scan never ran" are distinguishable
            // in a log. They were not, and that cost a debugging cycle on the restart journey.
            log.debug("Stuck-work recovery: nothing to recover (examined {}, cutoff {})", examined, cutoff);
        }
        return new ScanReport(examined, rescheduled, exhausted, skipped);
    }

    /**
     * Reaps one candidate. The candidate is a snapshot from the scan, so every value it carries is re-tested inside
     * the conditional update; a claim that moved on in the meantime simply yields {@code NOT_STUCK}.
     */
    private ReapOutcome reapOne(StuckClaimCandidate candidate, Instant cutoff) {
        try {
            ReapOutcome outcome = notificationService.recoverStuckProcessing(
                    candidate.tenantId(), candidate.id(), candidate.dispatchGeneration(), candidate.claimSeq(),
                    cutoff);
            switch (outcome) {
                case RESCHEDULED -> {
                    meters.counter("notify.recovery.reaped", "outcome", "rescheduled").increment();
                    log.warn("Recovered stuck notification {} (channel={}, attempt {}/{}): rescheduled for retry",
                            candidate.id(), candidate.channel(), candidate.attemptCount(), candidate.maxAttempts());
                }
                case EXHAUSTED -> {
                    meters.counter("notify.recovery.reaped", "outcome", "exhausted").increment();
                    log.warn("Recovered stuck notification {} (channel={}, attempt {}/{}): attempt budget spent, "
                                    + "failed and dead-lettered without a known provider outcome",
                            candidate.id(), candidate.channel(), candidate.attemptCount(), candidate.maxAttempts());
                }
                case NOT_STUCK -> meters.counter("notify.recovery.reaped", "outcome", "skipped").increment();
            }
            return outcome;
        } catch (RuntimeException e) {
            // One unreapable candidate must not abandon the rest of the scan.
            meters.counter("notify.recovery.errors").increment();
            log.warn("Stuck-work recovery failed for notification {} ({})", candidate.id(), e.getClass().getName());
            return ReapOutcome.NOT_STUCK;
        }
    }
}
