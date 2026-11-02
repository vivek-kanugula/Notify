package com.notify.delivery.resilience;

import com.notify.common.model.Channel;
import com.notify.common.model.TenantStatus;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.delivery.provider.EmailProvider;
import com.notify.delivery.provider.EmailSendRequest;
import com.notify.delivery.provider.email.EmailChannelAdapter;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A local resilience refusal must reach the orchestrator as a {@code Deferred}, on <b>every</b> guarded channel
 * (/ , ).
 *
 * <p>The guard signals a refusal by throwing {@link ProviderDeferredException}, which each adapter translates at its
 * provider-call site. If an adapter does not translate it, the exception escapes as a plain {@code RuntimeException}
 * and the orchestrator treats it as an unknown remote effect - a retryable provider failure that <b>consumes an
 * attempt</b> for a call that was never made. That is precisely what forbids, so it is pinned here per channel
 * rather than left to the webhook path alone.
 */
class ChannelDeferralTranslationTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** An email provider that refuses the way the guard does: before any request. */
    private static final class RefusingEmailProvider implements EmailProvider {

        @Override
        public String name() {
            return "resend";
        }

        @Override
        public DeliveryResult send(EmailSendRequest request) {
            throw new ProviderDeferredException(ProviderCallGuard.CIRCUIT_OPEN, Duration.ofSeconds(30));
        }
    }

    private static DeliveryOrchestrator orchestrator(EmailChannelAdapter adapter) {
        TenantService tenantService = tenantId -> Optional.of(TenantStatus.ACTIVE);
        RetryProperties retry = new RetryProperties(Duration.ofSeconds(10), Duration.ofMinutes(10), 0.0);
        return new DeliveryOrchestrator(List.of(adapter), tenantService,
                new ExponentialBackoffCalculator(retry, RandomGenerator.getDefault()),
                new TransientDbRetry(new DeliveryExceptionClassifier()), CLOCK, 50, Duration.ofMinutes(5));
    }

    private static ClaimSnapshot emailClaim(int deferralCount) {
        return new ClaimSnapshot(UUID.randomUUID(), UUID.randomUUID(), 0, 1, 1, 1, 5, deferralCount,
                Channel.EMAIL, "someone@example.com", "subject", "body", null, null, "sender@example.com", NOW, NOW);
    }

    @Test
    @DisplayName("an EMAIL guard refusal is deferred, and costs no attempt")
    void emailRefusalIsDeferred() {
        EmailChannelAdapter adapter = new EmailChannelAdapter(new RefusingEmailProvider());

        DeliveryResult result = adapter.send(emailClaim(0));

        assertThat(result)
                .as("without translation this would escape as a RuntimeException and consume an attempt")
                .isInstanceOf(DeliveryResult.Deferred.class);
        DeliveryResult.Deferred deferred = (DeliveryResult.Deferred) result;
        assertThat(deferred.code()).isEqualTo(ProviderCallGuard.CIRCUIT_OPEN);
        assertThat(deferred.retryAfter()).isEqualTo(Duration.ofSeconds(30));

        PersistableOutcome outcome = orchestrator(adapter).plan(emailClaim(0), result, NOW, NOW);
        assertThat(outcome)
                .as("a Defer writes no attempt row and refunds the claim's attempt")
                .isInstanceOf(PersistableOutcome.Defer.class);
    }

    @Test
    @DisplayName("at the deferral cap the attempt row names notify-system, because nothing was sent")
    void atTheCapTheProviderIsNotNamed() {
        EmailChannelAdapter adapter = new EmailChannelAdapter(new RefusingEmailProvider());
        DeliveryResult refusal = adapter.send(emailClaim(0));

        // deferralCount == maxDeferrals, so the orchestrator stops deferring and records a real retryable failure.
        PersistableOutcome outcome = orchestrator(adapter).plan(emailClaim(50), refusal, NOW, NOW);

        assertThat(outcome).isInstanceOf(PersistableOutcome.Retry.class);
        assertThat(((PersistableOutcome.Retry) outcome).provider())
                .as("same rule as the tenant gate and the webhook destination refusals")
                .isEqualTo(DeliveryOrchestrator.SYSTEM_PROVIDER);
    }

    @Test
    @DisplayName("an ordinary email failure still names the provider")
    void anOrdinaryFailureStillNamesTheProvider() {
        EmailChannelAdapter adapter = new EmailChannelAdapter(new RefusingEmailProvider());
        DeliveryResult failure =
                new DeliveryResult.RetryableFailure("EMAIL_PROVIDER_5XX", "Provider answered 503", 503, null);

        PersistableOutcome outcome = orchestrator(adapter).plan(emailClaim(0), failure, NOW, NOW);

        assertThat(((PersistableOutcome.Retry) outcome).provider()).isEqualTo("resend");
    }
}
