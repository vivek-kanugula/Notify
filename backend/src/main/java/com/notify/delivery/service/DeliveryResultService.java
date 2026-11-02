package com.notify.delivery.service;

import com.notify.common.model.AttemptOutcome;
import com.notify.common.model.Channel;
import com.notify.delivery.entity.DeliveryAttemptEntity;
import com.notify.delivery.model.FencedOutException;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.delivery.repository.DeliveryAttemptRepository;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.notification.model.NotificationTransitioner;
import com.notify.notification.service.NotificationService;
import com.notify.observability.metrics.NotifyMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Writes one delivery result in a single atomic transaction (plan sections 3.3 and 4.2).
 *
 * <p>Invariant order: (1) the fenced conditional status update through {@link NotificationTransitioner} (which also
 * writes the status history and publishes the transition event); zero rows throws {@link FencedOutException} and
 * rolls everything back; (2) only then the attempt row and, for FAILED, the dead-letter outbox row. A failure at any
 * step rolls back all of them, so a half-written result can never be observed.
 */
@Service
public class DeliveryResultService {

private static final int MAX_ERROR_CODE = 64;
    /** EMAIL keeps 's limit exactly; changing it would change established email behaviour. */
    private static final int EMAIL_SNIPPET_LIMIT = 1024;
    /** Width of delivery_attempts.response_snippet (VARCHAR(4096), V1, immutable). */
    private static final int MAX_SNIPPET_COLUMN = 4096;

    private final NotificationTransitioner transitioner;
    private final NotificationService notificationService;
    private final DeliveryAttemptRepository attemptRepository;
    private final NotifyMetrics metrics;
    private final Clock clock;
    private final Map<Channel, DeliveryResultSideEffect> sideEffects;

    public DeliveryResultService(
            NotificationTransitioner transitioner,
            NotificationService notificationService,
            DeliveryAttemptRepository attemptRepository,
            NotifyMetrics metrics,
            Clock clock,
            List<DeliveryResultSideEffect> sideEffects
    ) {
        this.transitioner = transitioner;
        this.notificationService = notificationService;
        this.attemptRepository = attemptRepository;
        this.metrics = metrics;
        this.clock = clock;
        Map<Channel, DeliveryResultSideEffect> byChannel = new EnumMap<>(Channel.class);
        for (DeliveryResultSideEffect effect : sideEffects) {
            DeliveryResultSideEffect previous = byChannel.put(effect.channel(), effect);
            if (previous != null) {
                throw new IllegalStateException("Two delivery result side effects claim channel " + effect.channel()
                        + ": " + previous.getClass().getName() + " and " + effect.getClass().getName());
            }
        }
        this.sideEffects = byChannel;
    }

    @Transactional(timeout = 5)
    public void persist(ClaimSnapshot claim, PersistableOutcome outcome) {
        switch (outcome) {
            case PersistableOutcome.Delivered d -> {
                fence(transitioner.completeDelivery(
                        claim.tenantId(), claim.id(), claim.generation(), claim.claimSeq(),
                        d.provider(), d.messageId()), claim);
                saveAttempt(claim, d.provider(), d.startedAt(), d.finishedAt(),
                        AttemptOutcome.SUCCESS, null, null, null, null);
                metrics.incrementDeliveryAttempt(claim.channel(), d.provider(), AttemptOutcome.SUCCESS.name());
                metrics.recordDeliveryDuration(claim.channel(), d.provider(),
                        Duration.between(d.startedAt(), d.finishedAt()));
                metrics.recordE2eDuration(claim.channel(), Duration.between(claim.createdAt(), clock.instant()));
                // / a channel whose delivery IS a durable write commits it here, after the fence and
                // inside this transaction. It must throw rather than skip, so a missing write rolls the result back
                // instead of reporting DELIVERED (condition C1).
                DeliveryResultSideEffect sideEffect = sideEffects.get(claim.channel());
                if (sideEffect == null) {
                    requireOptional(claim.channel());
                } else {
                    sideEffect.afterFencedDelivery(claim, d);
                }
            }
            case PersistableOutcome.Retry r -> {
                String code = limit(r.errorCode(), MAX_ERROR_CODE);
                fence(transitioner.retryDelivery(
                        claim.tenantId(), claim.id(), claim.generation(), claim.claimSeq(),
                        r.nextRetryAt(), code), claim);
                saveAttempt(claim, r.provider(), r.startedAt(), r.finishedAt(),
                        AttemptOutcome.RETRYABLE_FAILURE, code, r.errorMessage(), r.httpStatus(), r.snippet());
                metrics.incrementDeliveryAttempt(claim.channel(), r.provider(), AttemptOutcome.RETRYABLE_FAILURE.name());
                metrics.recordDeliveryDuration(claim.channel(), r.provider(),
                        Duration.between(r.startedAt(), r.finishedAt()));
                metrics.incrementRetry(claim.channel());
            }
            case PersistableOutcome.Fail f -> {
                String code = limit(f.errorCode(), MAX_ERROR_CODE);
                fence(transitioner.failDelivery(
                        claim.tenantId(), claim.id(), claim.generation(), claim.claimSeq(), code), claim);
                saveAttempt(claim, f.provider(), f.startedAt(), f.finishedAt(),
                        f.attemptOutcome(), code, f.errorMessage(), f.httpStatus(), f.snippet());
                metrics.incrementDeliveryAttempt(claim.channel(), f.provider(), f.attemptOutcome().name());
                metrics.recordDeliveryDuration(claim.channel(), f.provider(),
                        Duration.between(f.startedAt(), f.finishedAt()));
                notificationService.recordDeadLetter(
                        claim.tenantId(), claim.id(), claim.channel(), claim.generation(), claim.deliveryCycle(),
                        claim.attemptCount(), f.dlqReason(), code, claim.traceContext(), clock.instant());
            }
            case PersistableOutcome.Defer d -> {
                // Deferral: the claim's attempt is refunded and no attempt row is written.
                fence(transitioner.deferDelivery(
                        claim.tenantId(), claim.id(), claim.generation(), claim.claimSeq(),
                        d.nextRetryAt(), limit(d.errorCode(), MAX_ERROR_CODE)), claim);
                metrics.incrementDeferral(claim.channel());
            }
        }
    }

    /**
     * Channels whose delivery <b>is</b> a durable local write, so the result is only a result if that write happened.
     *
     * <p>Condition C1 says a missing in-app persistence hook must roll the whole delivery back. A {@code null} lookup
     * is exactly that case - the bean was not registered, excluded by a condition, or filtered out of the injected
     * list - and it is indistinguishable, from here, from a channel that legitimately needs no hook. Listing the
     * channels that require one is therefore the only way the check can fail closed: EMAIL and WEBHOOK hand their
     * payload to a remote provider and register nothing, so for them an absent hook is correct and silence is right.
     *
     * <p>Kept as a set rather than a flag on {@link Channel} on purpose: this is a fact about the delivery result
     * pipeline, not about the channel enum, which is shared with ingress and the public API.
     */
    private static final java.util.Set<Channel> CHANNELS_REQUIRING_SIDE_EFFECT =
            java.util.Collections.unmodifiableSet(java.util.EnumSet.of(Channel.IN_APP));

    /**
     * Throws when the channel may not complete a delivery without its side effect.
     *
     * <p>An {@link IllegalStateException} from inside {@code persist} rolls the transaction back, so neither the
     * DELIVERED status, its history row, nor the SUCCESS attempt can commit - which is the whole point: a
     * misconfigured deployment must fail the delivery loudly (the worker's recovery policy then classifies it)
     * rather than report an inbox message the recipient will never see.
     */
    private static void requireOptional(Channel channel) {
        if (CHANNELS_REQUIRING_SIDE_EFFECT.contains(channel)) {
            throw new IllegalStateException(
                    "No DeliveryResultSideEffect is registered for channel " + channel
                            + ", whose delivery is a durable local write. Refusing to report DELIVERED without it "
                            + ".");
        }
    }

    private static void fence(boolean updated, ClaimSnapshot claim) {
        if (!updated) {
            throw new FencedOutException("Result fenced out for notification " + claim.id()
                    + " generation " + claim.generation() + " claimSeq " + claim.claimSeq());
        }
    }

    private void saveAttempt(
            ClaimSnapshot claim, String provider, Instant startedAt, Instant finishedAt,
            AttemptOutcome outcome, String errorCode, String errorMessage, Integer httpStatus, String snippet
    ) {
        DeliveryAttemptEntity attempt = new DeliveryAttemptEntity();
        attempt.setTenantId(claim.tenantId());
        attempt.setNotificationId(claim.id());
        attempt.setDeliveryCycle(claim.deliveryCycle());
        attempt.setAttemptNumber(claim.attemptCount());
        attempt.setProvider(limit(provider, 64));
        attempt.setStartedAt(startedAt);
        attempt.setFinishedAt(finishedAt);
        attempt.setOutcome(outcome);
        attempt.setErrorCode(limit(errorCode, MAX_ERROR_CODE));
        attempt.setErrorMessage(limit(errorMessage, 1024));
        attempt.setHttpStatus(httpStatus);
        attempt.setResponseSnippet(limit(snippet, snippetLimitFor(claim.channel())));
        attempt.setLatencyMs(Math.max(0L, Duration.between(startedAt, finishedAt).toMillis()));
        attempt.setCreatedAt(clock.instant());
        attemptRepository.saveAndFlush(attempt);
    }

    /**
     * How much of a response snippet this channel may store in {@code delivery_attempts.response_snippet}.
     *
     * <p>The column is {@code VARCHAR(4096)}. EMAIL keeps the 1024 characters established, so no email behaviour
     * changes. WEBHOOK stores up to the column's width, which is what approved: the sender already bounds its
     * snippet to {@code notify.webhook.stored-snippet-bytes} UTF-8 bytes including the truncation marker, and a value
     * of at most 4096 UTF-8 bytes can never be more than 4096 characters, so it survives this write intact.
     */
    private static int snippetLimitFor(Channel channel) {
        return channel == Channel.EMAIL ? EMAIL_SNIPPET_LIMIT : MAX_SNIPPET_COLUMN;
    }

    private static String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
