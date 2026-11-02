package com.notify.schema;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.TestcontainerDatasourceGuard;
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
 * V10: Remove Google OAuth / OIDC artifacts and enforce universal password-based tenant users.
 * Verifies fresh migration V1->V10, upgrade V9->V10, column removal, and email uniqueness.
 */
@org.springframework.test.context.ContextConfiguration(initializers = TestcontainerDatasourceGuard.class)
class FlywayV10MigrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;

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

    private UUID seedTenant(String schema, String slug) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + schema + ".tenants (id, name, slug) VALUES (?, ?, ?)",
                id, "Org " + slug, slug);
        return id;
    }

    @Test
    @DisplayName("V10-1 fresh V1->V10 drops google_sub and restores uq_tenant_users_email on lower(email)")
    void freshMigrationThroughV10() {
        String schema = "test_fresh_v10";
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schema);

        Flyway flyway = flyway(schema, "10");
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("10");

        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'tenant_users'",
                String.class, schema);
        assertThat(columns)
                .contains("id", "tenant_id", "email", "password_hash")
                .doesNotContain("google_sub");

        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename = 'tenant_users'",
                String.class, schema);
        assertThat(indexes)
                .contains("uq_tenant_users_email", "uq_tenant_users_tenant_email")
                .doesNotContain("tenant_users_google_sub_key", "uq_tenant_users_password_email");
    }

    @Test
    @DisplayName("V10-2 upgrading V9->V10 removes google_sub column and enforces password_hash NOT NULL")
    void upgradeV9ToV10PreservesExistingPasswordRows() {
        String schema = "test_upgrade_v10";
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schema);

        Flyway toV9 = flyway(schema, "9");
        toV9.migrate();
        assertThat(toV9.info().current().getVersion().getVersion()).isEqualTo("9");

        UUID tenantId = seedTenant(schema, "v10-upgrade-" + UUID.randomUUID());
        jdbcTemplate.update("INSERT INTO " + schema + ".tenant_users (id, tenant_id, email, password_hash, email_verified) "
                        + "VALUES (?, ?, ?, 'pw_hash_123', true)",
                UUID.randomUUID(), tenantId, "user@example.com");

        Flyway toV10 = flyway(schema, "10");
        toV10.migrate();
        assertThat(toV10.info().current().getVersion().getVersion()).isEqualTo("10");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + schema + ".tenant_users WHERE email = 'user@example.com'", Long.class))
                .isEqualTo(1L);

        // Verify attempting to insert a row without password_hash fails
        UUID secondTenant = seedTenant(schema, "v10-second-" + UUID.randomUUID());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO " + schema + ".tenant_users (id, tenant_id, email, password_hash, email_verified) VALUES (?, ?, ?, NULL, true)",
                UUID.randomUUID(), secondTenant, "nopw@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Verify duplicate email is forbidden across tenants
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO " + schema + ".tenant_users (id, tenant_id, email, password_hash, email_verified) VALUES (?, ?, ?, 'pw_hash_456', true)",
                UUID.randomUUID(), secondTenant, "USER@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

