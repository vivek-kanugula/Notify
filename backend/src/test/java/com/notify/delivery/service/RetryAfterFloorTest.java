package com.notify.delivery.service;

import com.notify.common.model.Channel;
import com.notify.common.model.TenantStatus;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.delivery.provider.webhook.WebhookResponseMapper;
import com.notify.delivery.provider.webhook.WebhookSendOutcome;
import com.notify.delivery.retry.ExponentialBackoffCalculator;
import com.notify.delivery.retry.RetryProperties;
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
 * A receiver's {@code Retry-After} as a <b>floor</b> on the next attempt (/ , decision as adjusted).
 *
 * <p>The adjusted policy, which this test pins in full:
 * <ul>
 *   <li>the existing retryable classification is kept, the attempt <b>is</b> consumed and its attempt-history row
 *       <b>is</b> written - a {@code 429} from a receiver is a real request, not a local refusal;</li>
 *   <li>a valid, bounded delta-seconds value raises {@code next_retry_at};</li>
 *   <li>it can <b>never shorten</b> the exponential backoff;</li>
 *   <li>exhaustion stays finite: it cannot buy another attempt;</li>
 *   <li>the attempt-free {@code Deferred} path remains reserved for local refusals before any provider contact.</li>
 * </ul>
 */
class RetryAfterFloorTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(5);

    /** A deterministic backoff: base 10 s, no jitter, so the floor's effect is unambiguous. */
    private static ExponentialBackoffCalculator backoff() {
        RetryProperties properties = new RetryProperties(Duration.ofSeconds(10), Duration.ofMinutes(10), 0.0);
        return new ExponentialBackoffCalculator(properties, RandomGenerator.getDefault());
    }

    private static DeliveryOrchestrator orchestrator() {
        // Every case here plans an outcome directly, so the tenant gate is never consulted.
        TenantService tenantService = tenantId -> Optional.of(TenantStatus.ACTIVE);
        return new DeliveryOrchestrator(List.of(), tenantService, backoff(),
                new TransientDbRetry(new DeliveryExceptionClassifier()), CLOCK, 50, MAX_RETRY_AFTER);
    }

    private static ClaimSnapshot claim(int attemptCount, int maxAttempts) {
        return new ClaimSnapshot(UUID.randomUUID(), UUID.randomUUID(), 0, 1, 1, attemptCount, maxAttempts, 0,
                Channel.WEBHOOK, "https://hooks.example.com/x", "subject", "body", null, null, null, NOW, NOW);
    }

    private static PersistableOutcome plan(ClaimSnapshot claim, Integer retryAfterSeconds) {
        DeliveryResult result = new DeliveryResult.RetryableFailure(
                "WEBHOOK_RATE_LIMITED", "Receiver answered 429", 429, null, retryAfterSeconds);
        return orchestrator().plan(claim, result, NOW, NOW);
    }

    @Test
    @DisplayName("a 429 consumes the attempt and is recorded: it is a request, not a local refusal")
    void a429IsARealAttempt() {
        PersistableOutcome outcome = plan(claim(1, 5), 60);

        assertThat(outcome).isInstanceOf(PersistableOutcome.Retry.class);
        PersistableOutcome.Retry retry = (PersistableOutcome.Retry) outcome;
        assertThat(retry.errorCode()).isEqualTo("WEBHOOK_RATE_LIMITED");
        assertThat(retry.httpStatus()).isEqualTo(429);
        // A Retry outcome writes a RETRYABLE_FAILURE attempt row and does not refund the claim's attempt; only
        // PersistableOutcome.Defer refunds, and that is reachable only from a local pre-call refusal.
        assertThat(outcome).isNotInstanceOf(PersistableOutcome.Defer.class);
    }

    @Test
    @DisplayName("a longer Retry-After raises next_retry_at")
    void aLongerRetryAfterRaisesTheDueTime() {
        PersistableOutcome.Retry retry = (PersistableOutcome.Retry) plan(claim(1, 5), 120);

        assertThat(retry.nextRetryAt())
                .as("the receiver asked for 120s and the backoff is 10s, so the floor wins")
                .isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    @DisplayName("a shorter Retry-After never shortens the backoff")
    void aShorterRetryAfterIsIgnored() {
        PersistableOutcome.Retry withHint = (PersistableOutcome.Retry) plan(claim(1, 5), 1);
        PersistableOutcome.Retry withoutHint = (PersistableOutcome.Retry) plan(claim(1, 5), null);

        assertThat(withHint.nextRetryAt())
                .as("1s cannot undercut the 10s backoff")
                .isEqualTo(withoutHint.nextRetryAt())
                .isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    @DisplayName("an excessive Retry-After is clamped, so a receiver cannot park work")
    void anExcessiveRetryAfterIsClamped() {
        PersistableOutcome.Retry retry = (PersistableOutcome.Retry) plan(claim(1, 5), 86_400);

        assertThat(retry.nextRetryAt())
                .as("clamped to notify.resilience.max-retry-after, not rejected")
                .isEqualTo(NOW.plus(MAX_RETRY_AFTER));
    }

    @Test
    @DisplayName("exhaustion stays finite: a Retry-After cannot buy another attempt")
    void exhaustionStaysFinite() {
        PersistableOutcome outcome = plan(claim(5, 5), 300);

        assertThat(outcome).isInstanceOf(PersistableOutcome.Fail.class);
        PersistableOutcome.Fail fail = (PersistableOutcome.Fail) outcome;
        assertThat(fail.dlqReason()).isEqualTo(com.notify.common.model.DlqReason.MAX_RETRIES_EXCEEDED);
    }

    @Test
    @DisplayName("only the delta-seconds form is honoured; an HTTP-date is recorded but never acted on")
    void onlyDeltaSecondsIsHonoured() {
        WebhookSendOutcome withSeconds = WebhookSendOutcome.response(429, null, false, 12, "90");
        WebhookSendOutcome withHttpDate =
                WebhookSendOutcome.response(429, null, false, 12, "Wed, 21 Oct 2026 07:28:00 GMT");
        WebhookSendOutcome withGarbage = WebhookSendOutcome.response(429, null, false, 12, "soon-ish");
        WebhookSendOutcome withNegative = WebhookSendOutcome.response(429, null, false, 12, "-5");

        assertThat(((DeliveryResult.RetryableFailure) WebhookResponseMapper.map(withSeconds)).retryAfterSeconds())
                .isEqualTo(90);
        assertThat(((DeliveryResult.RetryableFailure) WebhookResponseMapper.map(withHttpDate)).retryAfterSeconds())
                .as("the HTTP-date form invites clock-skew bugs and is deliberately not acted on")
                .isNull();
        assertThat(((DeliveryResult.RetryableFailure) WebhookResponseMapper.map(withGarbage)).retryAfterSeconds())
                .isNull();
        assertThat(((DeliveryResult.RetryableFailure) WebhookResponseMapper.map(withNegative)).retryAfterSeconds())
                .isNull();
        // The raw value stays in the attempt message either way, which is what established.
        assertThat(((DeliveryResult.RetryableFailure) WebhookResponseMapper.map(withHttpDate)).message())
                .contains("Retry-After: Wed, 21 Oct 2026 07:28:00 GMT");
    }

    @Test
    @DisplayName("a 503 carries the floor, and a permanent 4xx carries none")
    void serverErrorsCarryTheFloorAndClientErrorsDoNot() {
        WebhookSendOutcome serverError = WebhookSendOutcome.response(503, null, false, 12, "45");
        WebhookSendOutcome clientError = WebhookSendOutcome.response(400, null, false, 12, "45");

        DeliveryResult retryable = WebhookResponseMapper.map(serverError);
        assertThat(retryable).isInstanceOf(DeliveryResult.RetryableFailure.class);
        assertThat(((DeliveryResult.RetryableFailure) retryable).retryAfterSeconds()).isEqualTo(45);

        assertThat(WebhookResponseMapper.map(clientError))
                .as("a permanent failure is not retried at all, so a floor would be meaningless")
                .isInstanceOf(DeliveryResult.PermanentFailure.class);
    }

    /**
     * The scope of , pinned exactly. The decision covers an actual {@code 429} or {@code 503} response and
     * nothing else; an earlier implementation applied the floor to every retryable outcome, which silently honoured
     * a {@code 408}, a {@code 500} and - had a transport failure ever carried a header - a failure with no response
     * at all. Each of those is still <b>recorded</b>, and each is still retried on the backoff policy's own schedule.
     */
    @Test
    @DisplayName("only 429 and 503 carry a floor: 408, another 5xx and a transport failure do not")
    void onlyTheTwoAcceptedStatusesCarryAFloor() {
        WebhookSendOutcome timeout408 = WebhookSendOutcome.response(408, null, false, 12, "90");
        WebhookSendOutcome serverError500 = WebhookSendOutcome.response(500, null, false, 12, "90");
        WebhookSendOutcome gateway504 = WebhookSendOutcome.response(504, null, false, 12, "90");
        WebhookSendOutcome rateLimited = WebhookSendOutcome.response(429, null, false, 12, "90");
        WebhookSendOutcome unavailable = WebhookSendOutcome.response(503, null, false, 12, "90");

        assertThat(floorOf(timeout408)).as("a 408 is retryable but outside ").isNull();
        assertThat(floorOf(serverError500)).as("a 500 is retryable but outside ").isNull();
        assertThat(floorOf(gateway504)).as("a 504 is retryable but outside ").isNull();
        assertThat(floorOf(rateLimited)).isEqualTo(90);
        assertThat(floorOf(unavailable)).isEqualTo(90);

        // The header is still recorded verbatim on every one of them, which is what established.
        assertThat(((DeliveryResult.RetryableFailure) WebhookResponseMapper.map(timeout408)).message())
                .contains("Retry-After: 90");
        assertThat(((DeliveryResult.RetryableFailure) WebhookResponseMapper.map(serverError500)).message())
                .contains("Retry-After: 90");
    }

    @Test
    @DisplayName("a transport failure carries no floor, and the backoff policy alone schedules it")
    void transportFailuresCarryNoFloor() {
        WebhookSendOutcome transportTimeout = WebhookSendOutcome.failure(
                WebhookSendOutcome.Failure.TIMEOUT, "Receiver did not answer in time", 5_000);
        WebhookSendOutcome unreachable = WebhookSendOutcome.failure(
                WebhookSendOutcome.Failure.UNREACHABLE, "Receiver could not be reached", 12);

        assertThat(floorOf(transportTimeout)).isNull();
        assertThat(floorOf(unreachable)).isNull();

        // And the orchestrator schedules such an outcome on the backoff alone: 10 s here, not something a receiver
        // chose - there was no receiver response at all.
        DeliveryResult result = WebhookResponseMapper.map(transportTimeout);
        PersistableOutcome.Retry retry = (PersistableOutcome.Retry) orchestrator()
                .plan(claim(1, 5), result, NOW, NOW);
        assertThat(retry.nextRetryAt()).isEqualTo(NOW.plusSeconds(10));
    }

    private static Integer floorOf(WebhookSendOutcome outcome) {
        DeliveryResult result = WebhookResponseMapper.map(outcome);
        assertThat(result).isInstanceOf(DeliveryResult.RetryableFailure.class);
        return ((DeliveryResult.RetryableFailure) result).retryAfterSeconds();
    }
}
