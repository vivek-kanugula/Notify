package com.notify.delivery.provider;

import com.notify.common.model.AttemptOutcome;
import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.common.model.TenantStatus;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.delivery.retry.ExponentialBackoffCalculator;
import com.notify.delivery.retry.RetryProperties;
import com.notify.delivery.service.DeliveryOrchestrator;
import com.notify.delivery.service.TransientDbRetry;
import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.tenant.service.TenantService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The channel seam introduced by / one orchestrator, one adapter per channel. The tenant gate, the retry and
 * exhaustion policy and the deferral cap stay in the orchestrator and must behave identically for both channels —
 * that shared behaviour is what these assertions protect.
 */
class DeliveryChannelAdapterRegistryTest {

    private static final UUID TENANT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");

    private Supplier<Optional<TenantStatus>> tenant = () -> Optional.of(TenantStatus.ACTIVE);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Records what it was asked to send and answers with a fixed result. */
    private static final class RecordingAdapter implements DeliveryChannelAdapter {
        private final Channel channel;
        private final String providerName;
        private final DeliveryResult result;
        private final RuntimeException toThrow;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<ClaimSnapshot> seen = new ArrayList<>();

        RecordingAdapter(Channel channel, String providerName, DeliveryResult result, RuntimeException toThrow) {
            this.channel = channel;
            this.providerName = providerName;
            this.result = result;
            this.toThrow = toThrow;
        }

        static RecordingAdapter answering(Channel channel, String providerName, DeliveryResult result) {
            return new RecordingAdapter(channel, providerName, result, null);
        }

        @Override
        public Channel channel() {
            return channel;
        }

        @Override
        public String providerName() {
            return providerName;
        }

        @Override
        public DeliveryResult send(ClaimSnapshot claim) {
            calls.incrementAndGet();
            seen.add(claim);
            if (toThrow != null) {
                throw toThrow;
            }
            return result;
        }
    }

    private static ClaimSnapshot claim(Channel channel, int attemptCount, int maxAttempts) {
        return new ClaimSnapshot(UUID.randomUUID(), TENANT_ID, 0, 1, 0, attemptCount, maxAttempts, 0,
                channel, channel == Channel.WEBHOOK ? "https://receiver.example.com/hook" : "user@example.com",
                "Subject", "body", null, null, null, NOW.minusSeconds(60), NOW.minusSeconds(600));
    }

    private DeliveryOrchestrator orchestrator(DeliveryChannelAdapter... adapters) {
        TenantService tenantService = id -> tenant.get();
        ExponentialBackoffCalculator backoff = new ExponentialBackoffCalculator(
                new RetryProperties(Duration.ofSeconds(30), Duration.ofMinutes(15), 0.2));
        TransientDbRetry dbRetry = new TransientDbRetry(new DeliveryExceptionClassifier(), new long[]{0, 0, 0});
        return new DeliveryOrchestrator(List.of(adapters), tenantService, backoff, dbRetry, clock, 50, java.time.Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("each claim goes to the adapter for its own channel and to no other")
    void dispatchesByChannel() {
        RecordingAdapter email = RecordingAdapter.answering(Channel.EMAIL, "resend", new DeliveryResult.Success("m-1"));
        RecordingAdapter webhook = RecordingAdapter.answering(Channel.WEBHOOK, "webhook", new DeliveryResult.Success(null));
        DeliveryOrchestrator orchestrator = orchestrator(email, webhook);

        PersistableOutcome emailOutcome = orchestrator.deliver(claim(Channel.EMAIL, 1, 5));
        PersistableOutcome webhookOutcome = orchestrator.deliver(claim(Channel.WEBHOOK, 1, 5));

        assertThat(email.calls.get()).isEqualTo(1);
        assertThat(webhook.calls.get()).isEqualTo(1);
        assertThat(email.seen.get(0).channel()).isEqualTo(Channel.EMAIL);
        assertThat(webhook.seen.get(0).channel()).isEqualTo(Channel.WEBHOOK);
        assertThat(((PersistableOutcome.Delivered) emailOutcome).provider())
.isEqualTo("resend");
        assertThat(((PersistableOutcome.Delivered) webhookOutcome).provider())
.isEqualTo("webhook");
    }

    @Test
    @DisplayName("a channel with no active adapter is a permanent CHANNEL_NOT_SUPPORTED failure, never a silent success")
    void missingAdapterIsPermanent() {
        DeliveryOrchestrator orchestrator = orchestrator(
                RecordingAdapter.answering(Channel.EMAIL, "resend", new DeliveryResult.Success("m-1")));

        PersistableOutcome outcome = orchestrator.deliver(claim(Channel.WEBHOOK, 1, 5));

        assertThat(outcome).isInstanceOf(PersistableOutcome.Fail.class);
        PersistableOutcome.Fail fail = (PersistableOutcome.Fail) outcome;
        assertThat(fail.errorCode()).isEqualTo("CHANNEL_NOT_SUPPORTED");
        assertThat(fail.attemptOutcome()).isEqualTo(AttemptOutcome.PERMANENT_FAILURE);
        assertThat(fail.provider())
.isEqualTo(DeliveryOrchestrator.SYSTEM_PROVIDER);
    }

    @Test
    @DisplayName("two adapters claiming one channel fail at construction rather than silently shadowing each other")
    void duplicateChannelIsRefused() {
        DeliveryChannelAdapter first = RecordingAdapter.answering(Channel.WEBHOOK, "webhook", new DeliveryResult.Success(null));
        DeliveryChannelAdapter second = RecordingAdapter.answering(Channel.WEBHOOK, "other", new DeliveryResult.Success(null));

        assertThatThrownBy(() -> orchestrator(first, second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEBHOOK");
    }

    @Test
    @DisplayName("the tenant gate runs before any adapter call, for every channel ()")
    void tenantGatePrecedesTheAdapter() {
        tenant = () -> Optional.of(TenantStatus.SUSPENDED);
        RecordingAdapter webhook = RecordingAdapter.answering(Channel.WEBHOOK, "webhook", new DeliveryResult.Success(null));

        PersistableOutcome outcome = orchestrator(webhook).deliver(claim(Channel.WEBHOOK, 1, 5));

        assertThat(webhook.calls.get()).isZero();
        PersistableOutcome.Fail fail = (PersistableOutcome.Fail) outcome;
        assertThat(fail.errorCode()).isEqualTo(DeliveryOrchestrator.TENANT_NOT_ACTIVE);
        assertThat(fail.dlqReason()).isEqualTo(DlqReason.TENANT_NOT_ACTIVE);
    }

    @Test
    @DisplayName("the shared retry and exhaustion policy applies to WEBHOOK exactly as to EMAIL")
    void retryPolicyIsShared() {
        DeliveryResult retryable = new DeliveryResult.RetryableFailure("WEBHOOK_SERVER_ERROR", "Receiver answered 503", 503, null);
        DeliveryOrchestrator orchestrator = orchestrator(
                RecordingAdapter.answering(Channel.WEBHOOK, "webhook", retryable));

        PersistableOutcome retry = orchestrator.deliver(claim(Channel.WEBHOOK, 1, 5));
        PersistableOutcome exhausted = orchestrator.deliver(claim(Channel.WEBHOOK, 5, 5));

        assertThat(retry).isInstanceOf(PersistableOutcome.Retry.class);
        assertThat(((PersistableOutcome.Retry) retry).nextRetryAt()).isAfter(NOW);
        assertThat(exhausted).isInstanceOf(PersistableOutcome.Fail.class);
        PersistableOutcome.Fail fail = (PersistableOutcome.Fail) exhausted;
        assertThat(fail.dlqReason()).isEqualTo(DlqReason.MAX_RETRIES_EXCEEDED);
        assertThat(fail.attemptOutcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
    }

    @Test
    @DisplayName("an adapter that throws becomes a retryable failure: an unknown remote effect is never called permanent")
    void throwingAdapterIsRetryable() {
        DeliveryOrchestrator orchestrator = orchestrator(new RecordingAdapter(
                Channel.WEBHOOK, "webhook", null, new IllegalStateException("boom")));

        PersistableOutcome outcome = orchestrator.deliver(claim(Channel.WEBHOOK, 1, 5));

        assertThat(outcome).isInstanceOf(PersistableOutcome.Retry.class);
        assertThat(((PersistableOutcome.Retry) outcome).errorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
    }

    @Test
    @DisplayName("an adapter returning null becomes a retryable failure rather than a NullPointerException")
    void nullResultIsRetryable() {
        DeliveryOrchestrator orchestrator = orchestrator(
                RecordingAdapter.answering(Channel.WEBHOOK, "webhook", null));

        PersistableOutcome outcome = orchestrator.deliver(claim(Channel.WEBHOOK, 1, 5));

        assertThat(((PersistableOutcome.Retry) outcome).errorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
    }

    @Test
    @DisplayName("the provider name written for an attempt comes from the channel's own adapter")
    void providerNameIsPerChannel() {
        RecordingAdapter email = RecordingAdapter.answering(Channel.EMAIL, "resend",
                new DeliveryResult.PermanentFailure("X", "x", 400, null));
        RecordingAdapter webhook = RecordingAdapter.answering(Channel.WEBHOOK, "webhook",
                new DeliveryResult.PermanentFailure("Y", "y", 404, null));
        DeliveryOrchestrator orchestrator = orchestrator(email, webhook);

        assertThat(((PersistableOutcome.Fail) orchestrator.deliver(claim(Channel.EMAIL, 1, 5))).provider())
.isEqualTo("resend");
        assertThat(((PersistableOutcome.Fail) orchestrator.deliver(claim(Channel.WEBHOOK, 1, 5))).provider())
.isEqualTo("webhook");
    }
}
