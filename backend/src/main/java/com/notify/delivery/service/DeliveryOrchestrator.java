package com.notify.delivery.service;

import com.notify.common.model.AttemptOutcome;
import com.notify.common.model.DlqReason;
import com.notify.common.model.TenantStatus;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.common.model.Channel;
import com.notify.delivery.provider.ChannelPreparationException;
import com.notify.delivery.provider.DeliveryChannelAdapter;
import com.notify.delivery.retry.ExponentialBackoffCalculator;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides the outcome of one claimed notification (plan section 10.3). Holds no transaction: the provider call
 * must never run inside a business transaction, so {@link #deliver} refuses to start inside one.
 * The decision itself ({@link #plan}) is pure and unit-tested.
 *
 * <p>One failure is deliberately <em>not</em> given an outcome: a {@link ChannelPreparationException} from an
 * adapter's preparation seam means an infrastructure failure before any provider request, so it is rethrown for the
 * worker's classifier and the recovery policy rather than being recorded as a provider delivery result.
 */
@Service
public class DeliveryOrchestrator {

    public static final String SYSTEM_PROVIDER = "notify-system";
    public static final String TENANT_NOT_ACTIVE = "TENANT_NOT_ACTIVE";

    private static final Logger log = LoggerFactory.getLogger(DeliveryOrchestrator.class);
    private static final Duration MIN_DEFERRAL = Duration.ofSeconds(1);

    private final Map<Channel, DeliveryChannelAdapter> adapters;
    private final TenantService tenantService;
    private final ExponentialBackoffCalculator backoff;
    private final TransientDbRetry dbRetry;
    private final Clock clock;
    private final int maxDeferrals;
    /** Upper bound on a receiver-supplied {@code Retry-After} floor (, ). */
    private final Duration maxRetryAfter;

    public DeliveryOrchestrator(
            List<DeliveryChannelAdapter> channelAdapters,
            TenantService tenantService,
            ExponentialBackoffCalculator backoff,
            TransientDbRetry dbRetry,
            Clock clock,
            @Value("${notify.resilience.max-deferrals:50}") int maxDeferrals,
            @Value("${notify.resilience.max-retry-after:5m}") Duration maxRetryAfter
    ) {
        Map<Channel, DeliveryChannelAdapter> byChannel = new EnumMap<>(Channel.class);
        for (DeliveryChannelAdapter adapter : channelAdapters) {
            DeliveryChannelAdapter previous = byChannel.put(adapter.channel(), adapter);
            if (previous != null) {
                throw new IllegalStateException("Two delivery adapters claim channel " + adapter.channel()
                        + ": " + previous.getClass().getName() + " and " + adapter.getClass().getName());
            }
        }
        this.adapters = byChannel;
        this.tenantService = tenantService;
        this.backoff = backoff;
        this.dbRetry = dbRetry;
        this.clock = clock;
        this.maxDeferrals = maxDeferrals;
        this.maxRetryAfter = maxRetryAfter;
    }

    public PersistableOutcome deliver(ClaimSnapshot claim) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Provider calls must not run inside a database transaction");
        }

        // Tenant gate (): a tenant that is not ACTIVE is blocked before any provider call.
        Optional<TenantStatus> tenantStatus = dbRetry.call(() -> tenantService.getTenantStatus(claim.tenantId()));
        if (tenantStatus.isEmpty() || tenantStatus.get() != TenantStatus.ACTIVE) {
            Instant now = clock.instant();
            String detail = "Tenant is " + tenantStatus.map(Enum::name).orElse("MISSING");
            return new PersistableOutcome.Fail(now, now, SYSTEM_PROVIDER,
                    AttemptOutcome.PERMANENT_FAILURE, DlqReason.TENANT_NOT_ACTIVE,
                    TENANT_NOT_ACTIVE, detail, null, null);
        }

        DeliveryChannelAdapter adapter = adapters.get(claim.channel());
        if (adapter == null) {
            // Defensive: each channel has its own topic and worker, so a record should never arrive without one.
            Instant now = clock.instant();
            return new PersistableOutcome.Fail(now, now, SYSTEM_PROVIDER,
                    AttemptOutcome.PERMANENT_FAILURE, DlqReason.PERMANENT_FAILURE,
                    "CHANNEL_NOT_SUPPORTED", "No delivery adapter is active for channel " + claim.channel(), null, null);
        }

        Instant startedAt = clock.instant();
        DeliveryResult result;
        try {
            result = adapter.send(claim);
        } catch (ChannelPreparationException e) {
            // An infrastructure failure raised before any provider request, from the adapter's explicit preparation
            // seam. There is no unknown remote effect to account for, so planning an outcome here would invent a
            // provider failure and spend an attempt of the notification's budget. Let it escape instead: the worker
            // normalizes it through DeliveryExceptionClassifier and the recovery policy pauses or stops the
            // container with the offset retained. The classifier reads the cause, not this wrapper.
            log.warn("{} delivery preparation failed before any provider request: {} (notificationId={}, claimSeq={})",
                    claim.channel(), rootClass(e), claim.id(), claim.claimSeq());
            throw e;
        } catch (RuntimeException e) {
            // Adapters must not throw; an unexpected exception means the remote effect is unknown.
            log.warn("{} delivery adapter threw {} (notificationId={}, claimSeq={})",
                    claim.channel(), e.getClass().getName(), claim.id(), claim.claimSeq());
            result = new DeliveryResult.RetryableFailure(
                    "PROVIDER_UNAVAILABLE", "Unexpected provider adapter error", null, null);
        }
        if (result == null) {
            result = new DeliveryResult.RetryableFailure(
                    "PROVIDER_UNAVAILABLE", "Provider returned no result", null, null);
        }
        Instant finishedAt = clock.instant();
        return plan(claim, result, startedAt, finishedAt);
    }

    /** Pure decision: maps a provider result and the budgets of the claim to the outcome to persist. */
    public PersistableOutcome plan(ClaimSnapshot claim, DeliveryResult result, Instant startedAt, Instant finishedAt) {
        String providerName = providerNameFor(claim.channel(), result);
        return switch (result) {
            case DeliveryResult.Success s ->
                    new PersistableOutcome.Delivered(startedAt, finishedAt, providerName, s.messageId());
            case DeliveryResult.RetryableFailure r ->
                    retryOrExhaust(claim, r.code(), r.message(), r.httpStatus(), r.snippet(), startedAt, finishedAt,
                            r.retryAfterSeconds());
            case DeliveryResult.PermanentFailure p ->
                    new PersistableOutcome.Fail(startedAt, finishedAt, providerName,
                            AttemptOutcome.PERMANENT_FAILURE, DlqReason.PERMANENT_FAILURE,
                            p.code(), p.message(), p.httpStatus(), p.snippet());
            case DeliveryResult.Deferred d -> {
                if (claim.deferralCount() >= maxDeferrals) {
                    // Deferral cap reached: counted as an ordinary retryable failure (consumes an attempt).
                    yield retryOrExhaust(claim, d.code(), "Deferral limit reached", null, null, startedAt, finishedAt);
                }
                Duration wait = d.retryAfter() == null || d.retryAfter().compareTo(MIN_DEFERRAL) < 0
                        ? MIN_DEFERRAL : d.retryAfter();
                yield new PersistableOutcome.Defer(startedAt, finishedAt, d.code(), finishedAt.plus(wait));
            }
        };
    }

    /**
     * Provider name for the attempt and notification rows; the system name when no adapter is active. The adapter
     * decides per result, so a refusal made before any provider contact is not attributed to the provider ().
     */
    private String providerNameFor(Channel channel, DeliveryResult result) {
        DeliveryChannelAdapter adapter = adapters.get(channel);
        if (adapter == null) {
            return SYSTEM_PROVIDER;
        }
        String provider = adapter.providerFor(result);
        return provider == null || provider.isBlank() ? adapter.providerName() : provider;
    }

    /** Root cause class name for a log line; never the message, which could carry tenant data. */
    private static String rootClass(Throwable t) {
        Throwable root = t;
        for (int i = 0; i < 16 && root.getCause() != null && root.getCause() != root; i++) {
            root = root.getCause();
        }
        return root.getClass().getName();
    }

    private PersistableOutcome retryOrExhaust(
            ClaimSnapshot claim, String code, String message, Integer httpStatus, String snippet,
            Instant startedAt, Instant finishedAt
    ) {
        return retryOrExhaust(claim, code, message, httpStatus, snippet, startedAt, finishedAt, null);
    }

    private PersistableOutcome retryOrExhaust(
            ClaimSnapshot claim, String code, String message, Integer httpStatus, String snippet,
            Instant startedAt, Instant finishedAt, Integer retryAfterSeconds
    ) {
        String providerName = providerNameFor(claim.channel(),
                new DeliveryResult.RetryableFailure(code, message, httpStatus, snippet));
        if (claim.attemptCount() >= claim.maxAttempts()) {
            // Exhaustion stays finite whatever the receiver asked for: a Retry-After can delay an attempt, never
            // buy another one (, ).
            return new PersistableOutcome.Fail(startedAt, finishedAt, providerName,
                    AttemptOutcome.RETRYABLE_FAILURE, DlqReason.MAX_RETRIES_EXCEEDED,
                    code, message, httpStatus, snippet);
        }
        Instant nextRetryAt = finishedAt.plus(nextDelay(claim, retryAfterSeconds));
        return new PersistableOutcome.Retry(startedAt, finishedAt, providerName,
                code, message, httpStatus, snippet, nextRetryAt);
    }

    /**
     * The next attempt's delay: the exponential backoff, raised to a receiver's {@code Retry-After} if it asked for
     * longer (/ , decision ).
     *
     * <p>Three properties, each deliberate. It is a <b>floor</b>, so {@code max} - a receiver can slow us down but
     * never shorten our own backoff, however small a value it sends. It is <b>clamped</b> to
     * {@code notify.resilience.max-retry-after}, so a receiver cannot park work for a day. And it changes only
     * <em>when</em> the next attempt happens, never <em>whether</em>: the attempt budget is untouched, this attempt
     * is already counted, and its attempt-history row is written like any other.
     */
    private Duration nextDelay(ClaimSnapshot claim, Integer retryAfterSeconds) {
        Duration backoffDelay = backoff.delay(claim.attemptCount());
        if (retryAfterSeconds == null || retryAfterSeconds <= 0) {
            return backoffDelay;
        }
        Duration requested = Duration.ofSeconds(retryAfterSeconds);
        Duration clamped = requested.compareTo(maxRetryAfter) > 0 ? maxRetryAfter : requested;
        return clamped.compareTo(backoffDelay) > 0 ? clamped : backoffDelay;
    }
}
