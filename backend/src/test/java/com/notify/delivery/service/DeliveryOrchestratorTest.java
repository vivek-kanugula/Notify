package com.notify.delivery.service;

import com.notify.common.model.AttemptOutcome;
import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.common.model.TenantStatus;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.delivery.provider.EmailProvider;
import com.notify.delivery.provider.EmailSendRequest;
import com.notify.delivery.provider.email.EmailChannelAdapter;
import com.notify.delivery.retry.ExponentialBackoffCalculator;
import com.notify.delivery.retry.RetryProperties;
import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.tenant.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R2, R3, R6, T1 (unit halves): outcome planning, tenant gate, provider-call guard. */
class DeliveryOrchestratorTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Records every send and returns whatever the script says. */
    private static final class ScriptedProvider implements EmailProvider {
        final List<EmailSendRequest> sent = new ArrayList<>();
        Supplier<DeliveryResult> script = () -> new DeliveryResult.Success("msg-1");

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public DeliveryResult send(EmailSendRequest request) {
            sent.add(request);
            return script.get();
        }
    }

    private final ScriptedProvider provider = new ScriptedProvider();
    private Supplier<Optional<TenantStatus>> tenant = () -> Optional.of(TenantStatus.ACTIVE);
    private final AtomicInteger tenantReads = new AtomicInteger();
    private DeliveryOrchestrator orchestrator;

    private static Random fixedRandom() {
        return new Random() {
            @Override
            public double nextDouble() {
                return 0.5; // jitter factor exactly 1.0
            }
        };
    }

    @BeforeEach
    void setUp() {
        TenantService tenantService = id -> {
            tenantReads.incrementAndGet();
            return tenant.get();
        };
        ExponentialBackoffCalculator backoff = new ExponentialBackoffCalculator(
                new RetryProperties(Duration.ofSeconds(30), Duration.ofMinutes(15), 0.2), fixedRandom());
        TransientDbRetry dbRetry = new TransientDbRetry(new DeliveryExceptionClassifier(), new long[]{0, 0, 0});
        orchestrator = new DeliveryOrchestrator(
                List.of(new EmailChannelAdapter(provider)), tenantService, backoff, dbRetry, CLOCK, 50, java.time.Duration.ofMinutes(5));
    }

    private static ClaimSnapshot claim(int attemptCount, int maxAttempts, int deferralCount) {
        return new ClaimSnapshot(UUID.randomUUID(), UUID.randomUUID(), 3, 4, 2, attemptCount, maxAttempts, deferralCount,
                Channel.EMAIL, "to@example.com", "Subject", "Body", null, null, "sender@example.com", NOW.minusSeconds(60), NOW.minusSeconds(600));
    }

    @Test
    @DisplayName("R6 the request carries the persisted sender and the stable key <notificationId>-<deliveryCycle>")
    void requestIdentityIsStable() {
        ClaimSnapshot claim = claim(1, 5, 0);
        orchestrator.deliver(claim);
        orchestrator.deliver(claim);

        assertThat(provider.sent).hasSize(2);
        for (EmailSendRequest request : provider.sent) {
            assertThat(request.idempotencyKey()).isEqualTo(claim.id() + "-2");
            assertThat(request.from()).isEqualTo("sender@example.com");
            assertThat(request.to()).isEqualTo("to@example.com");
            assertThat(request.subject()).isEqualTo("Subject");
            assertThat(request.text()).isEqualTo("Body");
        }
    }

    @Test
    @DisplayName("R2 success is DELIVERED with the provider name and message id")
    void success() {
        PersistableOutcome outcome = orchestrator.deliver(claim(1, 5, 0));
        assertThat(outcome).isInstanceOfSatisfying(PersistableOutcome.Delivered.class, d -> {
            assertThat(d.provider()).isEqualTo("scripted");
            assertThat(d.messageId()).isEqualTo("msg-1");
            assertThat(d.startedAt()).isEqualTo(NOW);
            assertThat(d.finishedAt()).isEqualTo(NOW);
        });
    }

    @Test
    @DisplayName("R2 retryable failure with budget left schedules now + backoff(attemptCount)")
    void retryableWithBudget() {
        provider.script = () -> new DeliveryResult.RetryableFailure("PROVIDER_UNAVAILABLE", "down", 503, "snip");
        PersistableOutcome first = orchestrator.deliver(claim(1, 5, 0));
        PersistableOutcome third = orchestrator.deliver(claim(3, 5, 0));

        assertThat(first).isInstanceOfSatisfying(PersistableOutcome.Retry.class, r -> {
            assertThat(r.nextRetryAt()).isEqualTo(NOW.plusSeconds(30));
            assertThat(r.errorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
            assertThat(r.httpStatus()).isEqualTo(503);
            assertThat(r.snippet()).isEqualTo("snip");
        });
        assertThat(third).isInstanceOfSatisfying(PersistableOutcome.Retry.class,
                r -> assertThat(r.nextRetryAt()).isEqualTo(NOW.plusSeconds(120)));
    }

    @Test
    @DisplayName("R2 retryable failure on the last attempt exhausts: FAILED + MAX_RETRIES_EXCEEDED + RETRYABLE_FAILURE attempt, no next retry")
    void retryableExhausted() {
        provider.script = () -> new DeliveryResult.RetryableFailure("RATE_LIMITED", "quota", 429, null);
        for (ClaimSnapshot claim : new ClaimSnapshot[]{claim(5, 5, 0), claim(6, 5, 0), claim(1, 1, 0)}) {
            assertThat(orchestrator.deliver(claim)).isInstanceOfSatisfying(PersistableOutcome.Fail.class, f -> {
                assertThat(f.attemptOutcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
                assertThat(f.dlqReason()).isEqualTo(DlqReason.MAX_RETRIES_EXCEEDED);
                assertThat(f.errorCode()).isEqualTo("RATE_LIMITED");
            });
        }
    }

    @Test
    @DisplayName("R2 permanent failure is FAILED + PERMANENT_FAILURE with a PERMANENT_FAILURE attempt, regardless of budget")
    void permanent() {
        provider.script = () -> new DeliveryResult.PermanentFailure("AUTHENTICATION_FAILED", "bad key", 401, null);
        assertThat(orchestrator.deliver(claim(1, 5, 0))).isInstanceOfSatisfying(PersistableOutcome.Fail.class, f -> {
            assertThat(f.attemptOutcome()).isEqualTo(AttemptOutcome.PERMANENT_FAILURE);
            assertThat(f.dlqReason()).isEqualTo(DlqReason.PERMANENT_FAILURE);
            assertThat(f.provider()).isEqualTo("scripted");
            assertThat(f.httpStatus()).isEqualTo(401);
        });
    }

    @Test
    @DisplayName("R3 a deferral under the cap is a Defer with now + retryAfter and consumes no attempt")
    void deferralUnderCap() {
        provider.script = () -> new DeliveryResult.Deferred("FAKE_DEFERRED", Duration.ofSeconds(45));
        assertThat(orchestrator.deliver(claim(1, 5, 49))).isInstanceOfSatisfying(PersistableOutcome.Defer.class, d -> {
            assertThat(d.nextRetryAt()).isEqualTo(NOW.plusSeconds(45));
            assertThat(d.errorCode()).isEqualTo("FAKE_DEFERRED");
        });
    }

    @Test
    @DisplayName("R3 a deferral wait shorter than one second is raised to one second")
    void deferralMinimumWait() {
        provider.script = () -> new DeliveryResult.Deferred("FAKE_DEFERRED", Duration.ZERO);
        assertThat(orchestrator.deliver(claim(1, 5, 0))).isInstanceOfSatisfying(PersistableOutcome.Defer.class,
                d -> assertThat(d.nextRetryAt()).isEqualTo(NOW.plusSeconds(1)));
    }

    @Test
    @DisplayName("R3 at the cap (50) a deferral counts as an ordinary retryable failure: Retry with budget, Fail when exhausted")
    void deferralCapConsumesAnAttempt() {
        provider.script = () -> new DeliveryResult.Deferred("FAKE_DEFERRED", Duration.ofSeconds(45));
        assertThat(orchestrator.deliver(claim(2, 5, 50))).isInstanceOfSatisfying(PersistableOutcome.Retry.class,
                r -> assertThat(r.nextRetryAt()).isEqualTo(NOW.plusSeconds(60)));
        assertThat(orchestrator.deliver(claim(5, 5, 50))).isInstanceOfSatisfying(PersistableOutcome.Fail.class,
                f -> assertThat(f.dlqReason()).isEqualTo(DlqReason.MAX_RETRIES_EXCEEDED));
    }

    @Test
    @DisplayName("T1 SUSPENDED, REVOKED and missing tenants make no provider call and fail with TENANT_NOT_ACTIVE")
    void tenantGate() {
        for (Optional<TenantStatus> status : List.of(
                Optional.of(TenantStatus.SUSPENDED), Optional.of(TenantStatus.REVOKED), Optional.<TenantStatus>empty())) {
            tenant = () -> status;
            PersistableOutcome outcome = orchestrator.deliver(claim(1, 5, 0));
            assertThat(outcome).isInstanceOfSatisfying(PersistableOutcome.Fail.class, f -> {
                assertThat(f.dlqReason()).isEqualTo(DlqReason.TENANT_NOT_ACTIVE);
                assertThat(f.attemptOutcome()).isEqualTo(AttemptOutcome.PERMANENT_FAILURE);
                assertThat(f.errorCode()).isEqualTo("TENANT_NOT_ACTIVE");
                assertThat(f.provider()).isEqualTo("notify-system");
                assertThat(f.startedAt()).isEqualTo(f.finishedAt());
                assertThat(f.errorMessage()).startsWith("Tenant is ");
            });
        }
        assertThat(provider.sent).as("no provider call for a non-active tenant").isEmpty();
    }

    @Test
    @DisplayName("T1 an ACTIVE tenant is sent to the provider")
    void activeTenantSends() {
        orchestrator.deliver(claim(1, 5, 0));
        assertThat(provider.sent).hasSize(1);
        assertThat(tenantReads).hasValue(1);
    }

    @Test
    @DisplayName("R2 an exception from the adapter, or a null result, is retryable PROVIDER_UNAVAILABLE (ambiguous effect)")
    void adapterMisbehaviour() {
        provider.script = () -> {
            throw new IllegalStateException("adapter bug with private@example.com inside");
        };
        assertThat(orchestrator.deliver(claim(1, 5, 0))).isInstanceOfSatisfying(PersistableOutcome.Retry.class, r -> {
            assertThat(r.errorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
            assertThat(r.errorMessage()).doesNotContain("private@");
        });

        provider.script = () -> null;
        assertThat(orchestrator.deliver(claim(1, 5, 0))).isInstanceOf(PersistableOutcome.Retry.class);
    }

    @Test
    @DisplayName("T1 a transient database failure reading the tenant is retried in memory before the provider is called")
    void transientTenantReadIsRetried() {
        AtomicInteger calls = new AtomicInteger();
        tenant = () -> {
            if (calls.incrementAndGet() <= 2) {
                throw new QueryTimeoutException("slow");
            }
            return Optional.of(TenantStatus.ACTIVE);
        };
        assertThat(orchestrator.deliver(claim(1, 5, 0))).isInstanceOf(PersistableOutcome.Delivered.class);
        assertThat(calls).hasValue(3);
        assertThat(provider.sent).hasSize(1);
    }

    @Test
    @DisplayName("T1 a non-transient tenant read failure is rethrown immediately and the provider is never called")
    void nonTransientTenantReadFails() {
        tenant = () -> {
            throw new DataIntegrityViolationException("schema");
        };
        assertThatThrownBy(() -> orchestrator.deliver(claim(1, 5, 0))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(provider.sent).isEmpty();
    }

    @Test
    @DisplayName("R2 the provider is never called inside a database transaction")
    void refusesToRunInsideTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> orchestrator.deliver(claim(1, 5, 0)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(provider.sent).isEmpty();
    }
}
