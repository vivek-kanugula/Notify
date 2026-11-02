package com.notify.outbox;

import com.notify.AbstractPostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayV5MigrationCompatibilityTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Fresh V1->V5 migration: cleanly migrates from empty schema to V5, establishes schema and all 3 indexes")
    void freshMigration_migratesToV5AndCreatesIndexes() {
        String schema = "test_fresh_v5";
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schema);

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .locations("classpath:db/migration")
                .target("5")
                .load();

        flyway.migrate();

        // Verify Flyway applied 5 migrations
        var info = flyway.info().current();
        assertThat(info.getVersion().getVersion()).isEqualTo("5");

        // Verify column next_attempt_at exists in outbox_events with NOT NULL
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
            SELECT column_name, is_nullable, column_default
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = 'outbox_events' AND column_name = 'next_attempt_at'
        """, schema);
        assertThat(columns).hasSize(1);
        assertThat(columns.get(0).get("is_nullable")).isEqualTo("NO");

        // Verify all 3 indexes exist: idx_outbox_pending (retained from V1), idx_outbox_pending_fresh, idx_outbox_pending_retries
        List<String> indexNames = jdbcTemplate.queryForList("""
            SELECT indexname
            FROM pg_indexes
            WHERE schemaname = ? AND tablename = 'outbox_events'
        """, String.class, schema);

        assertThat(indexNames).contains(
                "idx_outbox_pending",
                "idx_outbox_pending_fresh",
                "idx_outbox_pending_retries"
        );
    }

    @Test
    @DisplayName("V4->V5 upgrade: preserves existing PENDING and PUBLISHED rows, backfills next_attempt_at from created_at, retains attempts")
    void v4ToV5Upgrade_preservesRowsAndBackfillsNextAttemptAt() {
        String schema = "test_upgrade_v4_v5";
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schema);

        // Step 1: Migrate up to V4 only
        Flyway flywayV4 = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .locations("classpath:db/migration")
                .target("4")
                .load();

        flywayV4.migrate();
        assertThat(flywayV4.info().current().getVersion().getVersion()).isEqualTo("4");

        // Step 2: Seed tenant and outbox rows into V4 schema (which does not have next_attempt_at)
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO %s.tenants (id, name, slug) VALUES (?, 'UpgradeTenant', 'upgrade-slug-%s')
        """.formatted(schema, UUID.randomUUID()), tenantId);

        UUID pendingFreshId = UUID.randomUUID();
        UUID pendingRetryId = UUID.randomUUID();
        UUID publishedId = UUID.randomUUID();

        Instant t1 = Instant.parse("2026-10-09T08:00:00Z");
        Instant t2 = Instant.parse("2026-10-09T08:30:00Z");
        Instant t3 = Instant.parse("2026-10-09T09:00:00Z");

        // Fresh pending row (attempts = 0)
        jdbcTemplate.update("""
            INSERT INTO %s.outbox_events (
                id, tenant_id, aggregate_type, aggregate_id, event_type,
                topic, message_key, payload, trace_context, status,
                attempts, created_at
            ) VALUES (?, ?, 'NOTIFICATION', ?, 'NOTIFICATION_QUEUED',
                'notify.dispatch.email.v1', 'k1', cast('{"v":1}' as jsonb), '00-trace1-01', 'PENDING',
                0, ?)
        """.formatted(schema), pendingFreshId, tenantId, UUID.randomUUID(), Timestamp.from(t1));

        // Retry pending row (attempts = 3)
        jdbcTemplate.update("""
            INSERT INTO %s.outbox_events (
                id, tenant_id, aggregate_type, aggregate_id, event_type,
                topic, message_key, payload, trace_context, status,
                attempts, created_at
            ) VALUES (?, ?, 'NOTIFICATION', ?, 'NOTIFICATION_QUEUED',
                'notify.dispatch.webhook.v1', 'k2', cast('{"v":1}' as jsonb), '00-trace2-01', 'PENDING',
                3, ?)
        """.formatted(schema), pendingRetryId, tenantId, UUID.randomUUID(), Timestamp.from(t2));

        // Published row (attempts = 1)
        jdbcTemplate.update("""
            INSERT INTO %s.outbox_events (
                id, tenant_id, aggregate_type, aggregate_id, event_type,
                topic, message_key, payload, trace_context, status,
                attempts, published_at, created_at
            ) VALUES (?, ?, 'NOTIFICATION', ?, 'NOTIFICATION_QUEUED',
                'notify.dispatch.inapp.v1', 'k3', cast('{"v":1}' as jsonb), null, 'PUBLISHED',
                1, ?, ?)
        """.formatted(schema), publishedId, tenantId, UUID.randomUUID(), Timestamp.from(t3.plusSeconds(2)), Timestamp.from(t3));

        // Step 3: Run migration to V5
        Flyway flywayV5 = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .locations("classpath:db/migration")
                .target("5")
                .load();

        flywayV5.migrate();
        assertThat(flywayV5.info().current().getVersion().getVersion()).isEqualTo("5");

        // Step 4: Verify upgraded data
        // Row 1: Fresh PENDING row
        Map<String, Object> freshRow = jdbcTemplate.queryForMap(
                "SELECT status, attempts, created_at, next_attempt_at FROM " + schema + ".outbox_events WHERE id = ?",
                pendingFreshId
        );
        assertThat(freshRow.get("status")).isEqualTo("PENDING");
        assertThat(((Number) freshRow.get("attempts")).intValue()).isEqualTo(0);
        assertThat(((Timestamp) freshRow.get("next_attempt_at")).toInstant()).isEqualTo(t1);

        // Row 2: Retry PENDING row
        Map<String, Object> retryRow = jdbcTemplate.queryForMap(
                "SELECT status, attempts, created_at, next_attempt_at FROM " + schema + ".outbox_events WHERE id = ?",
                pendingRetryId
        );
        assertThat(retryRow.get("status")).isEqualTo("PENDING");
        assertThat(((Number) retryRow.get("attempts")).intValue()).isEqualTo(3);
        assertThat(((Timestamp) retryRow.get("next_attempt_at")).toInstant()).isEqualTo(t2);

        // Row 3: PUBLISHED row
        Map<String, Object> pubRow = jdbcTemplate.queryForMap(
                "SELECT status, attempts, created_at, next_attempt_at, published_at FROM " + schema + ".outbox_events WHERE id = ?",
                publishedId
        );
        assertThat(pubRow.get("status")).isEqualTo("PUBLISHED");
        assertThat(((Number) pubRow.get("attempts")).intValue()).isEqualTo(1);
        assertThat(((Timestamp) pubRow.get("next_attempt_at")).toInstant()).isEqualTo(t3);
        assertThat(pubRow.get("published_at")).isNotNull();
    }
}
