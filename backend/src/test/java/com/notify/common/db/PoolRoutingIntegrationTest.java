package com.notify.common.db;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.quartz.service.NotificationSchedulerService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1, P2, P3 (exhaustion timing), P5, P6 (enabled half): full Boot context with the email worker pool enabled.
 * The listener itself is not started here (no Kafka wiring is needed to prove pool routing).
 */
@TestPropertySource(properties = {
        "notify.worker.email.enabled=true",
        "notify.worker.email.auto-startup=false",
        // A syntactically valid but unreachable broker: Spring's test-context cache pauses and restarts idle contexts, and a
        // restart must never fail merely because the (never-started) listener container is asked to build a consumer.
        "spring.kafka.bootstrap-servers=localhost:1"
})
class PoolRoutingIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    @Qualifier("primaryPool")
    private HikariDataSource primaryPool;

    @Autowired
    @Qualifier("workerPool")
    private HikariDataSource workerPool;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private NotificationSchedulerService schedulerService;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired(required = false)
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @PersistenceContext
    private EntityManager entityManager;

    private static int active(HikariDataSource pool) {
        var mx = pool.getHikariPoolMXBean();
        return mx == null ? 0 : mx.getActiveConnections();
    }

    private record Sample(int primaryActive, int workerActive, int jdbcPid, int jpaPid) {}

    private Sample sampleInsideTransaction() {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        return tt.execute(status -> {
            Integer jdbcPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
            Number jpaPid = (Number) entityManager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult();
            return new Sample(active(primaryPool), active(workerPool), jdbcPid, jpaPid.intValue());
        });
    }

    @Test
    @DisplayName("P1 the routing bean is the single primary DataSource and exposes exactly the primary and worker targets")
    void routingBeanWiring() {
        assertThat(dataSource).isInstanceOf(PoolRoutingDataSource.class);
        var resolved = ((PoolRoutingDataSource) dataSource).getResolvedDataSources();
        assertThat(resolved).containsOnlyKeys(WorkerPoolScope.PoolKey.PRIMARY, WorkerPoolScope.PoolKey.WORKER);
        assertThat(resolved.get(WorkerPoolScope.PoolKey.PRIMARY)).isSameAs(primaryPool);
        assertThat(resolved.get(WorkerPoolScope.PoolKey.WORKER)).isSameAs(workerPool);
        assertThat(primaryPool.getPoolName()).isEqualTo("notify-primary");
        assertThat(workerPool.getPoolName()).isEqualTo("notify-worker");
        // approved worker settings (/ ; size amended by / to serve every channel worker:
        // 3 listener threads + 1 health poll per enabled worker + 2 headroom)
        // (/ ): the shared worker pool now carries three channel workers, so the documented formula
        // "3 listener threads + 1 health poll per enabled worker + 2 headroom" gives 14, not 10. Still an exact
        // assertion on the configured value - only the accepted value changed.
        assertThat(workerPool.getMaximumPoolSize()).isEqualTo(14);
        assertThat(workerPool.getConnectionTimeout()).isEqualTo(3000L);
        assertThat(workerPool.getDataSourceProperties().getProperty("socketTimeout")).isEqualTo("10");
        assertThat(workerPool.getDataSourceProperties().getProperty("connectTimeout")).isEqualTo("3");
        assertThat(workerPool.getDataSourceProperties().getProperty("loginTimeout")).isEqualTo("3");
        // primary pool keeps Boot/Hikari defaults (no worker settings leaked into it)
        assertThat(primaryPool.getMaximumPoolSize()).isEqualTo(10);
        assertThat(primaryPool.getDataSourceProperties().getProperty("socketTimeout")).isNull();
    }

    @Test
    @DisplayName("P1 transactions outside a scope use the primary pool; inside a scope JPA, JdbcTemplate and the whole transaction use one worker connection")
    void transactionsRouteByScope() {
        Sample ingress = sampleInsideTransaction();
        assertThat(ingress.primaryActive()).isGreaterThanOrEqualTo(1);
        assertThat(ingress.workerActive()).isZero();
        assertThat(ingress.jdbcPid()).isEqualTo(ingress.jpaPid());

        Sample worker;
        try (WorkerPoolScope ignored = WorkerPoolScope.enter()) {
            worker = sampleInsideTransaction();
        }
        assertThat(worker.workerActive()).isEqualTo(1);
        assertThat(worker.primaryActive()).isZero();
        assertThat(worker.jdbcPid()).as("JPA and JdbcTemplate share one worker connection").isEqualTo(worker.jpaPid());
        assertThat(worker.jdbcPid()).as("worker backend differs from the primary backend").isNotEqualTo(ingress.jdbcPid());

        // back to primary after the scope
        Sample after = sampleInsideTransaction();
        assertThat(after.primaryActive()).isGreaterThanOrEqualTo(1);
        assertThat(after.workerActive()).isZero();
    }

    @Test
    @DisplayName("P2 scope is restored after normal exit and after an exception, nests correctly, and does not leak to the next task on a reused thread")
    void scopeCleanup() throws Exception {
        assertThat(WorkerPoolScope.isActive()).isFalse();

        try (WorkerPoolScope outer = WorkerPoolScope.enter()) {
            assertThat(WorkerPoolScope.isActive()).isTrue();
            try (WorkerPoolScope inner = WorkerPoolScope.enter()) {
                assertThat(WorkerPoolScope.isActive()).isTrue();
            }
            assertThat(WorkerPoolScope.isActive()).as("inner close restores the outer scope").isTrue();
        }
        assertThat(WorkerPoolScope.isActive()).isFalse();

        assertThatThrownBy(() -> {
            try (WorkerPoolScope ignored = WorkerPoolScope.enter()) {
                throw new IllegalArgumentException("boom");
            }
        }).isInstanceOf(IllegalArgumentException.class);
        assertThat(WorkerPoolScope.isActive()).as("closed after exception").isFalse();

        // double close is harmless
        WorkerPoolScope scope = WorkerPoolScope.enter();
        scope.close();
        scope.close();
        assertThat(WorkerPoolScope.isActive()).isFalse();

        // no leakage to the next task on the same (reused) thread
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> first = single.submit(() -> {
                try (WorkerPoolScope ignored = WorkerPoolScope.enter()) {
                    return WorkerPoolScope.isActive();
                }
            });
            Future<Boolean> second = single.submit(WorkerPoolScope::isActive);
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isFalse();
        } finally {
            single.shutdownNow();
        }
    }

    @Test
    @DisplayName("P2 entering a scope inside an active transaction is refused and leaves the thread unscoped")
    void enterInsideTransactionIsRefused() {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tt.execute(status -> {
            WorkerPoolScope.enter();
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction is already active");
        assertThat(WorkerPoolScope.isActive()).isFalse();
    }

    @Test
    @DisplayName("P3 an exhausted worker pool fails connection acquisition within about 3 s and recovers when connections are returned")
    void exhaustedWorkerPoolFailsWithinAcquisitionBound() throws Exception {
        List<Connection> held = new ArrayList<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < workerPool.getMaximumPoolSize(); i++) {
                held.add(workerPool.getConnection());
            }

            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            Future<Long> attempt = executor.submit(() -> {
                long begin = System.nanoTime();
                try (WorkerPoolScope ignored = WorkerPoolScope.enter()) {
                    tt.execute(status -> jdbcTemplate.queryForObject("SELECT 1", Integer.class));
                    throw new AssertionError("expected acquisition failure");
                } catch (RuntimeException expected) {
                    Throwable root = expected;
                    boolean sawHikariTimeout = false;
                    while (root != null) {
                        if (root instanceof java.sql.SQLTransientConnectionException) {
                            sawHikariTimeout = true;
                            assertThat(root.getMessage()).contains("notify-worker").contains("request timed out");
                        }
                        root = root.getCause();
                    }
                    assertThat(sawHikariTimeout).as("cause chain contains the Hikari pool timeout").isTrue();
                    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
                }
            });
            long elapsedMs = attempt.get(15, TimeUnit.SECONDS);
            assertThat(elapsedMs).as("fails at the 3000 ms acquisition bound").isBetween(2500L, 6000L);
        } finally {
            for (Connection c : held) {
                c.close();
            }
            executor.shutdownNow();
        }

        // recovered
        try (WorkerPoolScope ignored = WorkerPoolScope.enter()) {
            Integer one = new TransactionTemplate(transactionManager)
                    .execute(status -> jdbcTemplate.queryForObject("SELECT 1", Integer.class));
            assertThat(one).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("P5 inside a worker scope JPA, JdbcTemplate and the Quartz trigger commit together")
    void quartzJoinsWorkerTransaction_commit() {
        UUID notificationId = UUID.randomUUID();
        UUID tenantId = tenantRepository.save(new TenantEntity("PoolCommitOrg", "pool-commit-" + UUID.randomUUID())).getId();
        Instant scheduledAt = Instant.now().plus(3, ChronoUnit.HOURS);

        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        try (WorkerPoolScope ignored = WorkerPoolScope.enter()) {
            tt.execute(status -> {
                insertScheduledNotification(notificationId, tenantId, scheduledAt);
                schedulerService.scheduleNotificationRelease(tenantId, notificationId, scheduledAt, 0);
                // uncommitted trigger is visible to the same-transaction JdbcTemplate: Quartz used this transaction's connection
                assertThat(triggerCount(notificationId)).isEqualTo(1);
                assertThat(active(primaryPool)).as("nothing borrowed from the primary pool").isZero();
                return null;
            });
        }

        assertThat(triggerCount(notificationId)).isEqualTo(1);
        assertThat(notificationCount(notificationId)).isEqualTo(1);
    }

    @Test
    @DisplayName("P5 inside a worker scope a rollback removes the JPA row, the JDBC row and the Quartz trigger")
    void quartzJoinsWorkerTransaction_rollback() {
        UUID notificationId = UUID.randomUUID();
        UUID tenantId = tenantRepository.save(new TenantEntity("PoolRollbackOrg", "pool-rollback-" + UUID.randomUUID())).getId();
        String jpaSlug = "pool-rollback-jpa-" + UUID.randomUUID();
        Instant scheduledAt = Instant.now().plus(4, ChronoUnit.HOURS);

        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> {
            try (WorkerPoolScope ignored = WorkerPoolScope.enter()) {
                tt.execute(status -> {
                    tenantRepository.saveAndFlush(new TenantEntity("PoolRollbackJpa", jpaSlug));
                    insertScheduledNotification(notificationId, tenantId, scheduledAt);
                    schedulerService.scheduleNotificationRelease(tenantId, notificationId, scheduledAt, 0);
                    assertThat(triggerCount(notificationId)).isEqualTo(1);
                    throw new IllegalStateException("force rollback");
                });
            }
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("force rollback");

        assertThat(triggerCount(notificationId)).isZero();
        assertThat(notificationCount(notificationId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenants WHERE slug = ?", Integer.class, jpaSlug)).isZero();
    }

    @Test
    @DisplayName("O1 observability: Hikari pool metrics are published for both named pools")
    void poolMetricsForBothPools() {
        org.junit.jupiter.api.Assumptions.assumeTrue(meterRegistry != null, "no MeterRegistry in this context");
        assertThat(meterRegistry.find("hikaricp.connections.max").tag("pool", "notify-primary").gauge()).isNotNull();
        var worker = meterRegistry.find("hikaricp.connections.max").tag("pool", "notify-worker").gauge();
        assertThat(worker).isNotNull();
        // The published gauge must track the configured maximum: 5 originally, 10 by / , 14 by / .
        assertThat(worker.value()).isEqualTo(14.0);
    }

    @Test
    @DisplayName("P6 with the worker enabled the db health component is UP and names both pools; readiness stays UP")
    void healthReportsRoutedPools() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        // The aggregate status also reflects unrelated components that are intentionally absent in tests (e.g. Redis),
        // so assert the database component, which is what the readiness group uses.
        String body = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        assertThat(json.at("/components/db/status").asText()).as(body).isEqualTo("UP");
        assertThat(body).contains("PRIMARY").contains("WORKER");
    }

    private void insertScheduledNotification(UUID notificationId, UUID tenantId, Instant scheduledAt) {
        jdbcTemplate.update("""
                INSERT INTO notifications (
                    id, tenant_id, channel, status, recipient, subject, body,
                    scheduled_at, dispatch_generation, delivery_cycle, max_attempts, attempt_count,
                    deferral_count, version, created_at, updated_at
                ) VALUES (?, ?, 'EMAIL', 'SCHEDULED', 'pool@example.com', 'Subject', 'Body',
                          ?, 0, 1, 5, 0, 0, 0, NOW(), NOW())
                """, notificationId, tenantId, java.sql.Timestamp.from(scheduledAt));
    }

    private int triggerCount(UUID notificationId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM qrtz_triggers WHERE trigger_name = ? AND trigger_group = ?",
                Integer.class, "release-trg-" + notificationId, NotificationSchedulerService.RELEASE_GROUP);
    }

    private int notificationCount(UUID notificationId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE id = ?", Integer.class, notificationId);
    }

}
