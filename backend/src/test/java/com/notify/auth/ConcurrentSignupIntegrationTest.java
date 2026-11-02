package com.notify.auth;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.TestcontainerDatasourceGuard;
import com.notify.auth.dto.SignupRequest;
import com.notify.auth.service.TenantAuthService;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.notify.tenant.repository.TenantUserRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

/**
 * Concurrent password signups for one email (/ slice 1).
 *
 * <p>The pre-check in {@code TenantAuthService.signup} is not a lock, so two requests can both pass it. The
 * database is what decides, and the loser must come back as the ordinary **409 EMAIL_ALREADY_REGISTERED** with
 * no constraint name, SQL state or statement text leaking into the error - and with **nothing left behind**: no
 * tenant row and no provisioned webhook endpoint from the rolled-back attempt.
 */
@org.springframework.test.context.ContextConfiguration(initializers = TestcontainerDatasourceGuard.class)
class ConcurrentSignupIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private TenantAuthService authService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private TenantUserRepository users;

    private String email;

    @AfterEach
    void tearDown() {
        if (email != null) {
            jdbc.update("DELETE FROM tenants WHERE id IN "
                    + "(SELECT tenant_id FROM tenant_users WHERE lower(email) = lower(?))", email);
        }
    }

    @Test
    @DisplayName("slice 1: two concurrent signups give one complete account and one safe 409")
    void concurrentSignupsGiveOneAccountAndOne409() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        email = "race-" + suffix + "@notify.local";
        SignupRequest first = new SignupRequest("RaceOrgA" + suffix, email, "Password123!");
        SignupRequest second = new SignupRequest("RaceOrgB" + suffix, email, "Password123!");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> a = () -> attempt(first);
            Callable<Object> b = () -> attempt(second);
            // Deadline on invokeAll itself, so an unfinished task is cancelled here rather than awaited.
            List<Future<Object>> results = pool.invokeAll(List.of(a, b), 60, TimeUnit.SECONDS);
            assertThat(results).as("neither signup hit the 60 s execution deadline")
                    .noneMatch(Future::isCancelled);
            Object one = results.get(0).get();
            Object two = results.get(1).get();

            long successes = List.of(one, two).stream()
                    .filter(TenantAuthService.SignupResult.class::isInstance).count();
            long conflicts = List.of(one, two).stream()
                    .filter(NotifyException.class::isInstance).count();
            assertThat(successes).as("exactly one account is created").isEqualTo(1);
            assertThat(conflicts).as("and exactly one caller is refused").isEqualTo(1);

            NotifyException refusal = (NotifyException) List.of(one, two).stream()
                    .filter(NotifyException.class::isInstance).findFirst().orElseThrow();
            assertThat(refusal.getErrorCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED);
            assertThat(refusal.getMessage())
                    .as("the established 409 message, with no SQL detail")
                    .isEqualTo("An account with this email already exists")
                    .doesNotContain("uq_tenant_users", "constraint", "SQL", "insert");
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                    .as("both worker threads terminated; nothing is left blocked on a row lock")
                    .isTrue();
        }

        // One account, and the winner is complete: its tenant exists and nothing from the loser remains.
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT tenant_id, password_hash FROM tenant_users WHERE lower(email) = lower(?)", email);
        assertThat(users).hasSize(1);
        assertThat(users.get(0).get("password_hash")).isNotNull();
        UUID tenantId = (UUID) users.get(0).get("tenant_id");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants WHERE id = ?", Long.class, tenantId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tenants WHERE name IN (?, ?)", Long.class,
                first.tenantName(), second.tenantName()))
                .as("no orphan tenant from the rolled-back attempt")
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM webhook_endpoints WHERE tenant_id NOT IN (SELECT id FROM tenants)",
                Long.class))
                .as("no orphan provisioning")
                .isZero();
    }


    /**
     * Forces the interleaving the test is about: both requests must pass the availability check **before** either
     * inserts, and the whole execution is bounded.
     *
     * <p>Only this test's email is stubbed on the real repository bean; every other argument keeps the
     * repository's behaviour. The stubbed answer is {@code Optional.empty()}, which is exactly what the real
     * query returns at that moment - no row exists yet - so the check is synchronised, not weakened.
     * {@code callRealMethod()} is not usable: the bean is an interface-backed Spring Data proxy and Mockito
     * raises {@code MockitoException} when asked to invoke a real method on it.
     *
     * <p><b>A barrier failure fails this test.</b> A timeout, an interruption or a broken barrier means the
     * interleaving under test did not happen, so it is recorded and rethrown rather than swallowed - an earlier
     * version continued past it, which could have let the suite pass on an uncoordinated run.
     */
    @Test
    @DisplayName("slice 1: coordinated concurrent signups - both pass the check, one account, one mapped 409")
    void coordinatedConcurrentSignupsGiveOneAccountAndOne409() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        email = "coord-" + suffix + "@notify.local";
        SignupRequest first = new SignupRequest("CoordOrgA" + suffix, email, "Password123!");
        SignupRequest second = new SignupRequest("CoordOrgB" + suffix, email, "Password123!");

        CyclicBarrier bothPassedTheCheck = new CyclicBarrier(2);
        AtomicInteger arrivals = new AtomicInteger();
        AtomicReference<Throwable> barrierFailure = new AtomicReference<>();
        doAnswer(invocation -> {
            arrivals.incrementAndGet();
            try {
                bothPassedTheCheck.await(20, TimeUnit.SECONDS);
            } catch (TimeoutException | BrokenBarrierException e) {
                barrierFailure.compareAndSet(null, e);
                throw new IllegalStateException("coordination failed: " + e.getClass().getName(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                barrierFailure.compareAndSet(null, e);
                throw new IllegalStateException("coordination interrupted", e);
            }
            return Optional.empty();
        }).when(users).findByEmail(argThat(email::equalsIgnoreCase));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Object one;
        Object two;
        try {
            // The deadline is on invokeAll itself: an unfinished task is cancelled here rather than awaited.
            List<Future<Object>> results = pool.invokeAll(
                    List.of(() -> attempt(first), () -> attempt(second)), 60, TimeUnit.SECONDS);
            assertThat(results).noneMatch(Future::isCancelled)
                    .as("neither signup hit the 60 s execution deadline");
            one = results.get(0).get();
            two = results.get(1).get();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                    .as("both worker threads terminated; nothing is left blocked on the barrier or a row lock")
                    .isTrue();
        }

        assertThat(barrierFailure.get()).as("the coordinated interleaving actually happened").isNull();
        assertThat(arrivals.get())
                .as("both callers reached the availability check before either inserted")
                .isEqualTo(2);

        assertThat(List.of(one, two)).filteredOn(TenantAuthService.SignupResult.class::isInstance).hasSize(1);
        NotifyException refusal = (NotifyException) List.of(one, two).stream()
                .filter(NotifyException.class::isInstance).findFirst().orElseThrow();
        assertThat(refusal.getErrorCode())
                .as("the loser is the mapped conflict, not a 500 and not a constraint error")
                .isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED);
        assertThat(refusal.getMessage())
                .isEqualTo("An account with this email already exists")
                .doesNotContain("uq_tenant_users", "constraint", "SQL");

        assertThat(jdbc.queryForList(
                "SELECT tenant_id FROM tenant_users WHERE lower(email) = lower(?)", email)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants WHERE name IN (?, ?)", Long.class,
                first.tenantName(), second.tenantName()))
                .as("no orphan tenant from the rolled-back attempt")
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM webhook_endpoints WHERE tenant_id NOT IN (SELECT id FROM tenants)",
                Long.class))
                .as("and no orphan provisioning")
                .isZero();
    }

    /** Runs a signup and returns either its result or the exception it raised, so both can be inspected. */
    private Object attempt(SignupRequest request) {
        try {
            return authService.signup(request, "junit", "127.0.0.1");
        } catch (RuntimeException e) {
            return e;
        }
    }
}
