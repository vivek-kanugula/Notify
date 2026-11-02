package com.notify.schema;

import com.notify.AbstractPostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M1 / M2: V6 adds notifications.sender and notifications.claim_seq (, / ).
 * Runs only in disposable schemas of the Testcontainers database.
 */
class FlywayV6MigrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Flyway flyway(String schema, String target) {
        var config = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private UUID seedTenant(String schema) {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + schema + ".tenants (id, name, slug) VALUES (?, 'V6Tenant', ?)",
                tenantId, "v6-slug-" + UUID.randomUUID());
        return tenantId;
    }

    private UUID seedNotification(String schema, UUID tenantId, String status, int generation) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO %s.notifications (id, tenant_id, channel, status, recipient, subject, body,
                                              dispatch_generation, attempt_count, queued_at, completed_at, cancel_reason)
                VALUES (?, ?, 'EMAIL', ?, 'user@example.com', 'subject', 'body', ?, 0, now(),
                        CASE WHEN ? = 'CANCELLED' THEN now() ELSE NULL END,
                        CASE WHEN ? = 'CANCELLED' THEN 'seed' ELSE NULL END)
                """.formatted(schema), id, tenantId, status, generation, status, status);
        return id;
    }

    private void seedPublishedOutbox(String schema, UUID tenantId, UUID notificationId) {
        jdbcTemplate.update("""
                INSERT INTO %s.outbox_events (id, tenant_id, aggregate_type, aggregate_id, event_type,
                                              topic, message_key, payload, status, attempts, published_at, created_at)
                VALUES (?, ?, 'NOTIFICATION', ?, 'NOTIFICATION_QUEUED', 'notify.dispatch.email.v1', ?,
                        cast('{"v":1}' as jsonb), 'PUBLISHED', 1, now(), now())
                """.formatted(schema), UUID.randomUUID(), tenantId, notificationId, notificationId.toString());
    }

    private Map<UUID, String> snapshotNotifications(String schema) {
        // Row image without the two V6 columns, normalized by PostgreSQL jsonb.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, ((to_jsonb(n) - 'sender') - 'claim_seq')::text AS img FROM " + schema + ".notifications n");
        Map<UUID, String> out = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            out.put((UUID) row.get("id"), (String) row.get("img"));
        }
        return out;
    }

    private Map<UUID, String> snapshotOutbox(String schema) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, to_jsonb(o)::text AS img FROM " + schema + ".outbox_events o");
        Map<UUID, String> out = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            out.put((UUID) row.get("id"), (String) row.get("img"));
        }
        return out;
    }

    @Test
    @DisplayName("M1 fresh V1->V6: sender nullable VARCHAR(255), claim_seq NOT NULL DEFAULT 0, CHECK >= 0, legacy insert still works")
    void freshMigration_createsColumnsDefaultsAndCheck() {
        String schema = "test_fresh_v6";
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schema);

        // Targeted at 6 on purpose: this case is about the V1->V6 result, and later migrations exist now (V7,
        // / ). Without the target the run would reach the newest version and the assertion would drift
        // with every future migration instead of pinning what V6 produces.
        Flyway flyway = flyway(schema, "6");
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("6");

        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, character_maximum_length, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = ? AND table_name = 'notifications' AND column_name IN ('sender', 'claim_seq')
                ORDER BY column_name
                """, schema);
        assertThat(columns).hasSize(2);

        Map<String, Object> claimSeq = columns.get(0);
        assertThat(claimSeq.get("column_name")).isEqualTo("claim_seq");
        assertThat(claimSeq.get("data_type")).isEqualTo("integer");
        assertThat(claimSeq.get("is_nullable")).isEqualTo("NO");
        assertThat(String.valueOf(claimSeq.get("column_default"))).isEqualTo("0");

        Map<String, Object> sender = columns.get(1);
        assertThat(sender.get("column_name")).isEqualTo("sender");
        assertThat(sender.get("data_type")).isEqualTo("character varying");
        assertThat(((Number) sender.get("character_maximum_length")).intValue()).isEqualTo(255);
        assertThat(sender.get("is_nullable")).isEqualTo("YES");
        assertThat(sender.get("column_default")).isNull();

        Integer checks = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = ? AND t.relname = 'notifications' AND c.conname = 'chk_notifications_claim_seq' AND c.contype = 'c'
                """, Integer.class, schema);
        assertThat(checks).isEqualTo(1);

        // Legacy-style insert that omits both new columns still succeeds and takes the defaults.
        UUID tenantId = seedTenant(schema);
        UUID legacyId = seedNotification(schema, tenantId, "QUEUED", 1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT sender, claim_seq FROM " + schema + ".notifications WHERE id = ?", legacyId);
        assertThat(row.get("sender")).isNull();
        assertThat(((Number) row.get("claim_seq")).intValue()).isZero();

        // CHECK constraint rejects a negative claim_seq.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE " + schema + ".notifications SET claim_seq = -1 WHERE id = ?", legacyId))
                .isInstanceOf(DataIntegrityViolationException.class);
        // NOT NULL rejects NULL claim_seq.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE " + schema + ".notifications SET claim_seq = NULL WHERE id = ?", legacyId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("M2 upgrade V5->V6: seeded QUEUED/CANCELLED notifications and PUBLISHED outbox rows are unchanged; sender NULL, claim_seq 0")
    void v5ToV6Upgrade_preservesExistingRowsExactly() {
        String schema = "test_upgrade_v5_v6";
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schema);

        Flyway flywayV5 = flyway(schema, "5");
        flywayV5.migrate();
        assertThat(flywayV5.info().current().getVersion().getVersion()).isEqualTo("5");

        // Seed rows that mirror the preserved historical development data shape:
        // PUBLISHED outbox rows linked to QUEUED and CANCELLED notifications.
        UUID tenantId = seedTenant(schema);
        UUID queuedA = seedNotification(schema, tenantId, "QUEUED", 1);
        UUID queuedB = seedNotification(schema, tenantId, "QUEUED", 1);
        UUID cancelled = seedNotification(schema, tenantId, "CANCELLED", 1);
        seedPublishedOutbox(schema, tenantId, queuedA);
        seedPublishedOutbox(schema, tenantId, queuedB);
        seedPublishedOutbox(schema, tenantId, cancelled);

        Map<UUID, String> notificationsBefore = snapshotNotifications(schema);
        Map<UUID, String> outboxBefore = snapshotOutbox(schema);
        assertThat(notificationsBefore).hasSize(3);
        assertThat(outboxBefore).hasSize(3);

        Flyway flywayV6 = flyway(schema, "6");
        flywayV6.migrate();
        assertThat(flywayV6.info().current().getVersion().getVersion()).isEqualTo("6");

        // Every pre-existing column value of every row is byte-for-byte unchanged.
        assertThat(snapshotNotifications(schema)).isEqualTo(notificationsBefore);
        assertThat(snapshotOutbox(schema)).isEqualTo(outboxBefore);

        // New columns carry only their defaults: nothing was backfilled or invented.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status, sender, claim_seq FROM " + schema + ".notifications");
        assertThat(rows).hasSize(3);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("sender")).isNull();
            assertThat(((Number) row.get("claim_seq")).intValue()).isZero();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + schema + ".outbox_events WHERE status = 'PUBLISHED'", Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + schema + ".outbox_events WHERE status = 'PENDING'", Integer.class)).isZero();
    }
}
