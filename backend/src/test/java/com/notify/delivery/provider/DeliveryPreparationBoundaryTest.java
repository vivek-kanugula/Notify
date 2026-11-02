package com.notify.delivery.provider;

import com.notify.common.model.Channel;
import com.notify.common.model.TenantStatus;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.delivery.retry.ExponentialBackoffCalculator;
import com.notify.delivery.retry.RetryProperties;
import com.notify.delivery.service.DeliveryOrchestrator;
import com.notify.delivery.service.TransientDbRetry;
import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.delivery.worker.classifier.ErrorCategory;
import com.notify.delivery.worker.exception.WorkerExceptions;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.tenant.service.TenantService;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;

import java.sql.SQLException;
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
 * The preparation boundary of the channel seam (/ database-recovery repair).
 *
 * <p>The defect this protects against: an infrastructure failure raised while an adapter prepared a delivery — before
 * any provider request — used to be swallowed by the orchestrator's blanket catch and recorded as
 * {@code PROVIDER_UNAVAILABLE}. That invents a provider outcome for a request that was never sent and spends an
 * attempt of the notification's delivery budget, so a long database outage could exhaust a notification and
 * dead-letter it without one webhook ever leaving the process.
 *
 * <p>What must hold instead: the failure escapes {@code deliver} unchanged, and the worker's accepted policy
 * decides, by the category of the <em>cause</em>. The assertions below check both halves — that no outcome is planned,
 * and that the category and recovery action are the ones the policy already defines.
 */
class DeliveryPreparationBoundaryTest {

    private static final UUID TENANT_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    private final DeliveryExceptionClassifier classifier = new DeliveryExceptionClassifier();
    private Supplier<Optional<TenantStatus>> tenant = () -> Optional.of(TenantStatus.ACTIVE);
    private final AtomicInteger tenantReads = new AtomicInteger();

    /** An adapter whose preparation step fails, and which records whether it was asked to send at all. */
    private static final class PreparingAdapter implements DeliveryChannelAdapter {
        private final RuntimeException preparationFailure;
        private final AtomicInteger sendCalls = new AtomicInteger();
        private final AtomicInteger providerCalls = new AtomicInteger();

        PreparingAdapter(RuntimeException preparationFailure) {
            this.preparationFailure = preparationFailure;
        }

        @Override
        public Channel channel() {
            return Channel.WEBHOOK;
        }

        @Override
        public String providerName() {
            return "webhook";
        }

        @Override
        public DeliveryResult send(ClaimSnapshot claim) {
            sendCalls.incrementAndGet();
            if (preparationFailure != null) {
                throw preparationFailure;      // raised before any provider contact
            }
            providerCalls.incrementAndGet();
            return new DeliveryResult.Success(null);
        }
    }

    private static ClaimSnapshot claim(int attemptCount, int maxAttempts) {
        return new ClaimSnapshot(UUID.randomUUID(), TENANT_ID, 0, 1, 0, attemptCount, maxAttempts, 0,
                Channel.WEBHOOK, "https://receiver.example.com/hook", "Subject", "body", null, null, null,
                NOW.minusSeconds(60), NOW.minusSeconds(600));
    }

    private DeliveryOrchestrator orchestrator(DeliveryChannelAdapter adapter) {
        TenantService tenantService = id -> {
            tenantReads.incrementAndGet();
            return tenant.get();
        };
        ExponentialBackoffCalculator backoff = new ExponentialBackoffCalculator(
                new RetryProperties(Duration.ofSeconds(30), Duration.ofMinutes(15), 0.2));
        TransientDbRetry dbRetry = new TransientDbRetry(classifier, new long[]{0, 0, 0});
        return new DeliveryOrchestrator(List.of(adapter), tenantService, backoff, dbRetry,
                Clock.fixed(NOW, ZoneOffset.UTC), 50, java.time.Duration.ofMinutes(5));
    }

    private static ChannelPreparationException preparationFailure(Throwable cause) {
        return new ChannelPreparationException("Webhook endpoint lookup failed before any provider request", cause);
    }

    private static JDBCConnectionException connectionLost() {
        return new JDBCConnectionException("could not obtain connection",
                new SQLException("connection refused", "08006"));
    }

    @Test
    @DisplayName("a preparation failure escapes deliver instead of becoming a provider outcome")
    void preparationFailureEscapes() {
        PreparingAdapter adapter = new PreparingAdapter(preparationFailure(connectionLost()));
        DeliveryOrchestrator orchestrator = orchestrator(adapter);

        assertThatThrownBy(() -> orchestrator.deliver(claim(1, 5)))
                .isInstanceOf(ChannelPreparationException.class)
                .hasRootCauseInstanceOf(SQLException.class);

        assertThat(tenantReads.get()).as("the tenant gate ran first and passed").isEqualTo(1);
        assertThat(adapter.sendCalls.get()).as("preparation was entered exactly once").isEqualTo(1);
        assertThat(adapter.providerCalls.get()).as("no provider request was made").isZero();
    }

    @Test
    @DisplayName("the wrapper decides no category: connectivity, contention, non-transient and unclassified causes all keep their accepted recovery action")
    void causeCategoriesSurviveTheWrapper() {
        record Case(String name, Throwable cause, ErrorCategory category,
                    ErrorCategory.RecoveryAction action, Class<?> normalized) {
        }
        List<Case> cases = List.of(
                new Case("connection lost", connectionLost(), ErrorCategory.CONNECTIVITY,
                        ErrorCategory.RecoveryAction.PAUSE_UNTIL_HEALTHY, WorkerExceptions.Connectivity.class),
                new Case("statement timeout", new QueryTimeoutException("statement timeout"), ErrorCategory.CONTENTION,
                        ErrorCategory.RecoveryAction.PAUSE_WITH_BACKOFF, WorkerExceptions.Contention.class),
                new Case("schema fault", new DataIntegrityViolationException("relation does not exist"),
                        ErrorCategory.NON_TRANSIENT, ErrorCategory.RecoveryAction.STOP,
                        WorkerExceptions.NonTransient.class),
                new Case("nothing recognisable", new IllegalStateException("surprise"), ErrorCategory.UNCLASSIFIED,
                        ErrorCategory.RecoveryAction.STOP, WorkerExceptions.Unclassified.class));

        List<String> wronglyCategorised = new ArrayList<>();
        for (Case c : cases) {
            ChannelPreparationException wrapped = preparationFailure(c.cause());

            ErrorCategory category = classifier.classify(wrapped);
            if (category != c.category() || category.recoveryAction() != c.action()
                    || !c.normalized().isInstance(classifier.normalize(wrapped))) {
                wronglyCategorised.add(c.name() + ": " + category + "/" + category.recoveryAction());
            }

            // And the same cause raised bare classifies identically: the wrapper adds a boundary, not a category.
            assertThat(classifier.classify(c.cause())).as("%s, unwrapped", c.name()).isEqualTo(c.category());
        }
        assertThat(wronglyCategorised).as("every cause keeps its accepted category and recovery action").isEmpty();
    }

    @Test
    @DisplayName("at the delivery-budget boundary a preparation failure still throws: no false provider exhaustion, no DLQ")
    void noFalseExhaustionAtTheBudgetBoundary() {
        PreparingAdapter adapter = new PreparingAdapter(preparationFailure(connectionLost()));
        DeliveryOrchestrator orchestrator = orchestrator(adapter);

        // attemptCount == maxAttempts: exactly the state in which a retryable provider failure becomes
        // Fail(MAX_RETRIES_EXCEEDED) and a dead-letter row. A preparation failure must not reach that decision.
        assertThatThrownBy(() -> orchestrator.deliver(claim(5, 5)))
                .isInstanceOf(ChannelPreparationException.class);
        assertThat(adapter.providerCalls.get()).isZero();
    }

    @Test
    @DisplayName("an ordinary adapter exception is unchanged: still a retryable PROVIDER_UNAVAILABLE outcome")
    void ordinaryAdapterExceptionStillBecomesAProviderOutcome() {
        DeliveryOrchestrator orchestrator = orchestrator(new PreparingAdapter(new IllegalStateException("boom")));

        PersistableOutcome outcome = orchestrator.deliver(claim(1, 5));

        assertThat(outcome).isInstanceOf(PersistableOutcome.Retry.class);
        assertThat(((PersistableOutcome.Retry) outcome).errorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
    }

    @Test
    @DisplayName("a database cause alone is not enough: the same exception thrown unwrapped stays a provider outcome")
    void onlyTheTypedSeamEscapes() {
        // Requirement: the boundary is positional, not a guess from the shape of a cause. An adapter that leaks a
        // data-access exception from somewhere else — for example after a request may already have been sent — must
        // keep the old, conservative treatment.
        DeliveryOrchestrator orchestrator = orchestrator(new PreparingAdapter(connectionLost()));

        PersistableOutcome outcome = orchestrator.deliver(claim(1, 5));

        assertThat(outcome).isInstanceOf(PersistableOutcome.Retry.class);
        assertThat(((PersistableOutcome.Retry) outcome).errorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
    }

    @Test
    @DisplayName("the tenant gate still runs before preparation: a blocked tenant fails without entering the adapter")
    void tenantGateStillRunsFirst() {
        tenant = () -> Optional.of(TenantStatus.SUSPENDED);
        PreparingAdapter adapter = new PreparingAdapter(preparationFailure(connectionLost()));

        PersistableOutcome outcome = orchestrator(adapter).deliver(claim(1, 5));

        assertThat(outcome).isInstanceOf(PersistableOutcome.Fail.class);
        assertThat(((PersistableOutcome.Fail) outcome).errorCode()).isEqualTo(DeliveryOrchestrator.TENANT_NOT_ACTIVE);
        assertThat(adapter.sendCalls.get()).as("preparation was never entered").isZero();
    }
}
