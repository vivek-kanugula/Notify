package com.notify.dlq.repository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V7, on its own disposable database (/ , / ; to ).
 *
 * <p>Two things are proven that a plain application-context run cannot: a **fresh** migration from V1 reaches V7,
 * and an **upgrade** from the V6 schema applies V7 while leaving existing rows exactly as they were. The upgrade
 * case is the one that matters for a real deployment, and it is why the fixture migrates to V6 first, writes rows,
 * then migrates the rest.
 *
 * <p>This suite owns its container and never touches a shared database: the V7 design is authorized for disposable
 * verification databases only.
 */
class DlqSchemaMigrationTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withCommand("postgres", "-c", "max_connections=50");

    static {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    /**
     * A URL for one database on this containers server.
     *
     * <p>Unpooled on purpose: every connection in this suite is opened and closed around a single statement, so a
     * dozen short-lived databases cannot exhaust the containers connection limit.
     */
    private static String urlFor(String database) {
        String base = POSTGRES.getJdbcUrl();
        int lastSlash = base.lastIndexOf('/');
        int query = base.indexOf('?', lastSlash);
        return query < 0
                ? base.substring(0, lastSlash + 1) + database
                : base.substring(0, lastSlash + 1) + database + base.substring(query);
    }

    private static Connection open(String database) throws Exception {
        return DriverManager.getConnection(urlFor(database), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void createDatabase(String name) throws Exception {
        try (Connection connection = open("postgres");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
    }

    private static org.flywaydb.core.Flyway flyway(String database, String target) {
        org.flywaydb.core.api.configuration.FluentConfiguration configuration = org.flywaydb.core.Flyway.configure()
                .dataSource(urlFor(database), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("db/migration");
        if (target != null) {
            configuration = configuration.target(org.flywaydb.core.api.MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }
    @Test
    @DisplayName("a fresh migration from V1 reaches V7 with both new constraints present")
    void freshMigrationReachesV7() throws Exception {
        String database = "fresh_" + UUID.randomUUID().toString().replace("-", "");
        createDatabase(database);

        flyway(database, null).migrate();

        assertThat(appliedVersions(database)).contains("7");
        assertThat(columnsOf(database, "dead_letter_entries"))
                .contains("last_event_id", "source_delivery_cycle", "source_generation");
        assertThat(indexesOf(database, "dead_letter_entries")).contains("uq_dlq_last_event");
        assertThat(columnsOf(database, "dlq_quarantine"))
                .contains("topic", "partition_number", "record_offset", "payload_byte_count", "payload_sha256",
                        "claimed_tenant_id", "claimed_notification_id", "claimed_generation",
                        "verified_tenant_id", "verified_notification_id", "association", "status");
        assertThat(indexesOf(database, "dlq_quarantine"))
                .contains("uq_dlq_quarantine_record", "idx_dlq_quarantine_open");
    }

    @Test
    @DisplayName("an upgrade from the V6 schema applies V7 and leaves existing rows unchanged")
    void upgradeFromV6PreservesRows() throws Exception {
        String database = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        createDatabase(database);

        // The state a real deployment is in before migrated to V6, with business rows already written.
        flyway(database, "6").migrate();
        UUID tenantId = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        execute(database, """
                INSERT INTO tenants (id, name, slug) VALUES ('%s', 'Upgrade Org', 'upgrade-%s')
                """.formatted(tenantId, tenantId.toString().substring(0, 8)));
        execute(database, """
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES ('%s', '%s', 'EMAIL', 'FAILED', 'someone@example.com', 'subject', 'body',
                        0, 1, 5, 5, now())
                """.formatted(notificationId, tenantId));
        execute(database, """
                INSERT INTO dead_letter_entries (tenant_id, notification_id, channel, reason, last_error_code,
                    attempt_count, status, replay_count)
                VALUES ('%s', '%s', 'EMAIL', 'MAX_RETRIES_EXCEEDED', 'PROVIDER_UNAVAILABLE', 5, 'OPEN', 2)
                """.formatted(tenantId, notificationId));

        flyway(database, null).migrate();

        assertThat(appliedVersions(database)).contains("6", "7");
        // The pre-existing row is intact, and its new columns are NULL - which the consumer treats as UNKNOWN and
        // quarantines rather than guessing (plan 2.2 rows 9 and 11).
        List<String> preserved = query(database, """
                SELECT reason || '|' || last_error_code || '|' || attempt_count || '|' || status || '|'
                       || replay_count || '|' || coalesce(source_delivery_cycle::text, 'NULL') || '|'
                       || coalesce(last_event_id::text, 'NULL')
                FROM dead_letter_entries WHERE notification_id = '%s'
                """.formatted(notificationId));
        assertThat(preserved)
                .containsExactly("MAX_RETRIES_EXCEEDED|PROVIDER_UNAVAILABLE|5|OPEN|2|NULL|NULL");
        assertThat(query(database,
                "SELECT status || '|' || delivery_cycle || '|' || attempt_count FROM notifications WHERE id = '%s'"
                        .formatted(notificationId)))
                .containsExactly("FAILED|1|5");
    }

    @Test
    @DisplayName("the partial unique index rejects a duplicate event id and allows many nulls")
    void partialUniqueIndexOnLastEventId() throws Exception {
        String database = "events_" + UUID.randomUUID().toString().replace("-", "");
        createDatabase(database);
        flyway(database, null).migrate();

        UUID tenantId = UUID.randomUUID();
        execute(database, "INSERT INTO tenants (id, name, slug) VALUES ('%s', 'Org', 'org-%s')"
                .formatted(tenantId, tenantId.toString().substring(0, 8)));
        UUID eventId = UUID.randomUUID();
        UUID first = insertFailedNotification(database, tenantId);
        UUID second = insertFailedNotification(database, tenantId);
        UUID third = insertFailedNotification(database, tenantId);
        UUID fourth = insertFailedNotification(database, tenantId);

        insertEntry(database, tenantId, first, eventId);
        assertThatThrownBy(() -> insertEntry(database, tenantId, second, eventId))
                .as("two entries must never claim the same applied event")
                .hasMessageContaining("uq_dlq_last_event");

        // Many nulls are fine: a legacy row has no marker, and the index excludes nulls.
        insertEntry(database, tenantId, third, null);
        insertEntry(database, tenantId, fourth, null);
        assertThat(query(database, "SELECT count(*)::text FROM dead_letter_entries WHERE last_event_id IS NULL"))
                .containsExactly("2");
    }

    @Test
    @DisplayName("the quarantine unique constraint rejects a duplicate topic, partition and offset")
    void quarantineRecordCoordinatesAreUnique() throws Exception {
        String database = "quarantine_" + UUID.randomUUID().toString().replace("-", "");
        createDatabase(database);
        flyway(database, null).migrate();

        insertQuarantine(database, "notify.dispatch.email.v1.DLT", 2, 77);
        assertThatThrownBy(() -> insertQuarantine(database, "notify.dispatch.email.v1.DLT", 2, 77))
                .as("a redelivered record is a conflict, not a second quarantine row")
                .hasMessageContaining("uq_dlq_quarantine_record");

        // A different offset on the same partition is a different record.
        insertQuarantine(database, "notify.dispatch.email.v1.DLT", 2, 78);
        assertThat(query(database, "SELECT count(*)::text FROM dlq_quarantine")).containsExactly("2");
    }

    // ── fixture helpers ─────────────────────────────────────────────────────────────────────────────────────────

    private static UUID insertFailedNotification(String database, UUID tenantId) throws Exception {
        UUID id = UUID.randomUUID();
        execute(database, """
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES ('%s', '%s', 'EMAIL', 'FAILED', 'someone@example.com', 'subject', 'body',
                        0, 1, 5, 5, now())
                """.formatted(id, tenantId));
        return id;
    }

    private static void insertEntry(String database, UUID tenantId, UUID notificationId, UUID eventId) {
        try {
            execute(database, """
                    INSERT INTO dead_letter_entries (tenant_id, notification_id, channel, reason, attempt_count,
                        status, replay_count, last_event_id, source_delivery_cycle, source_generation)
                    VALUES ('%s', '%s', 'EMAIL', 'PERMANENT_FAILURE', 1, 'OPEN', 0, %s, 1, 0)
                    """.formatted(tenantId, notificationId, eventId == null ? "NULL" : "'" + eventId + "'"));
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static void insertQuarantine(String database, String topic, int partition, long offset) {
        try {
            execute(database, """
                    INSERT INTO dlq_quarantine (topic, partition_number, record_offset, dlt_reason, association)
                    VALUES ('%s', %d, %d, 'POISON_PAYLOAD', 'UNPARSABLE')
                    """.formatted(topic, partition, offset));
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static void execute(String database, String sql) throws Exception {
        try (Connection connection = open(database);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> query(String database, String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection connection = open(database);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private static List<String> appliedVersions(String database) throws Exception {
        return query(database, "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL");
    }

    private static List<String> columnsOf(String database, String table) throws Exception {
        return query(database,
                "SELECT column_name FROM information_schema.columns WHERE table_name = '" + table + "'");
    }

    private static List<String> indexesOf(String database, String table) throws Exception {
        return query(database, "SELECT indexname FROM pg_indexes WHERE tablename = '" + table + "'");
    }
}
