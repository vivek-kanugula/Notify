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
 * V8 (/ slice 1): the partial unique index making "one password account per email" durable.
 *
 * <p>Disposable schemas of the Testcontainers database only, like the V6 suite. V1-V7 are untouched by this
 * migration. Proved here: the fresh path, the upgrade path with rows preserved, what the index does and does not
 * constrain, and that a pre-existing duplicate makes the migration fail instead of skipping or deduplicating.
 */
@org.springframework.test.context.ContextConfiguration(initializers = TestcontainerDatasourceGuard.class)
class FlywayV8MigrationTest extends AbstractPostgresIntegrationTest {

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

    private void seedPasswordUser(String schema, UUID tenantId, String email) {
        jdbcTemplate.update("INSERT INTO " + schema + ".tenant_users (id, tenant_id, email, password_hash, "
                        + "email_verified) VALUES (?, ?, ?, 'hash', true)",
                UUID.randomUUID(), tenantId, email);
    }

    private void seedGoogleUser(String schema, UUID tenantId, String email, String googleSub) {
        jdbcTemplate.update("INSERT INTO " + schema + ".tenant_users (id, tenant_id, email, google_sub, "
                        + "email_verified) VALUES (?, ?, ?, ?, true)",
                UUID.randomUUID(), tenantId, email, googleSub);
    }

    @Test
    @DisplayName("V8-1 fresh V1->V8 creates the partial index on email where password_hash is not null")
    void freshMigrationCreatesThePartialIndex() {
        String schema = "test_fresh_v8";
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schema);

        // Targeted at 8: this case is about what V8 produces, and V9 now follows it.
        Flyway flyway = flyway(schema, "8");
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("8");

        List<Map<String, Object>> indexes = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = 'tenant_users'"
                        + " AND indexname = 'uq_tenant_users_password_email'", schema);
        assertThat(indexes).hasSize(1);
        String definition = ((String) indexes.get(0).get("indexdef")).toLowerCase();
        assertThat(definition).contains("unique");
        assertThat(definition)
                .as("case-insensitive, matching V3's key so the two indexes agree")
                .contains("lower((email)::text)");
        assertThat(definition)
                .as("partial: Google-only rows are outside this constraint - though V3 still covers them")
                .contains("where (password_hash is not null)");
    }

    @Test
    @DisplayName("V8-2 a second password account for one email is refused, case-insensitively")
    void aSecondPasswordAccountIsRefused() {
        String schema = "test_scope_v8";
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        flyway(schema, "8").migrate();

        UUID first = seedTenant(schema, "scope-one-" + UUID.randomUUID());
        UUID second = seedTenant(schema, "scope-two-" + UUID.randomUUID());
        seedPasswordUser(schema, first, "shared@example.com");

        assertThatThrownBy(() -> seedPasswordUser(schema, second, "shared@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> seedPasswordUser(schema, second, "SHARED@example.com"))
                .as("the key is lower(email), so case does not create a second password account")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("V8-2b ordering: at V8, before V9, V3's global index still refuses a same-email Google row")
    void atV8TheGlobalIndexStillBlocksCoexistence() {
        String schema = "test_blocker_v8";
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        flyway(schema, "8").migrate();

        UUID first = seedTenant(schema, "blocker-one-" + UUID.randomUUID());
        UUID second = seedTenant(schema, "blocker-two-" + UUID.randomUUID());
        seedPasswordUser(schema, first, "both@example.com");

        // This is the state of the schema today, not the desired end state. requires a Google identity to get
        // its own tenant even when the email is already registered, and V3's uq_tenant_users_global_lower_email
        // forbids it. Relaxing V3 is a separate reviewed decision with its own forward migration; until then this
        // assertion documents precisely why Google identity creation stays unapproved.
        assertThatThrownBy(() -> seedGoogleUser(
                schema, second, "both@example.com", "google-sub-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_tenant_users_global_lower_email");
    }
    @Test
    @DisplayName("V8-3 upgrading V7->V8 preserves existing rows and then enforces the index")
    void upgradePreservesRowsAndEnforces() {
        String schema = "test_upgrade_v8";
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schema);

        Flyway toV7 = flyway(schema, "7");
        toV7.migrate();
        assertThat(toV7.info().current().getVersion().getVersion()).isEqualTo("7");

        UUID tenantId = seedTenant(schema, "upgrade-" + UUID.randomUUID());
        seedPasswordUser(schema, tenantId, "kept@example.com");
        seedGoogleUser(schema, tenantId, "google-kept@example.com", "google-sub-" + UUID.randomUUID());

        Flyway toV8 = flyway(schema, "8");
        toV8.migrate();
        assertThat(toV8.info().current().getVersion().getVersion()).isEqualTo("8");

        assertThat(jdbcTemplate.queryForList("SELECT email FROM " + schema + ".tenant_users ORDER BY email"))
                .as("both pre-existing rows survive the upgrade untouched")
                .extracting(row -> row.get("email"))
                .containsExactly("google-kept@example.com", "kept@example.com");

        UUID other = seedTenant(schema, "upgrade-other-" + UUID.randomUUID());
        assertThatThrownBy(() -> seedPasswordUser(schema, other, "kept@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("V8-4 V8's precondition is already guaranteed by V3, so no duplicate can exist to break it")
    void thePreconditionIsAlreadyGuaranteed() {
        String schema = "test_dupe_v8";
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        flyway(schema, "7").migrate();

        UUID first = seedTenant(schema, "dupe-one-" + UUID.randomUUID());
        UUID second = seedTenant(schema, "dupe-two-" + UUID.randomUUID());
        seedPasswordUser(schema, first, "duplicate@example.com");

        // On a V7 schema a duplicate password email cannot even be seeded: V3's global index refuses it. That is
        // why V8 needs no backfill and cannot fail on existing data for any database that reached V7. After V9
        // the global index is gone, and the password invariant is carried by V8's partial index alone - which is
        // what V9-2 asserts.
        assertThatThrownBy(() -> seedPasswordUser(schema, second, "duplicate@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_tenant_users_global_lower_email");

        Flyway toV8 = flyway(schema, "8");
        toV8.migrate();
        assertThat(toV8.info().current().getVersion().getVersion()).isEqualTo("8");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + schema + ".tenant_users WHERE email = 'duplicate@example.com'",
                Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("V9-1 fresh V1->V9 keeps the password index and drops the global one")
    void freshMigrationThroughV9() {
        String schema = "test_fresh_v9";
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schema);

        Flyway flyway = flyway(schema, "9");
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("9");

        assertThat(indexNames(schema))
                .contains("uq_tenant_users_password_email", "uq_tenant_users_tenant_email")
                .doesNotContain("uq_tenant_users_global_lower_email");
    }

    @Test
    @DisplayName("V9-2 after V9 a Google row may share a password email, in a different tenant only")
    void v9PermitsSameEmailAcrossTenants() {
        String schema = "test_coexist_v9";
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        flyway(schema, "9").migrate();

        UUID first = seedTenant(schema, "coexist-one-" + UUID.randomUUID());
        UUID second = seedTenant(schema, "coexist-two-" + UUID.randomUUID());
        seedPasswordUser(schema, first, "both@example.com");

        seedGoogleUser(schema, second, "both@example.com", "google-sub-" + UUID.randomUUID());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + schema + ".tenant_users WHERE email = 'both@example.com'", Long.class))
                .isEqualTo(2L);

        assertThatThrownBy(() -> seedGoogleUser(
                schema, first, "both@example.com", "google-sub-" + UUID.randomUUID()))
                .as("uq_tenant_users_tenant_email from V1 still forbids the same email twice in one tenant")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> seedPasswordUser(schema, second, "BOTH@example.com"))
                .as("and the password invariant is retained, case-insensitively")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("V9-3 upgrading V8->V9 preserves existing rows")
    void upgradeToV9PreservesRows() {
        String schema = "test_upgrade_v9";
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        flyway(schema, "8").migrate();

        UUID tenantId = seedTenant(schema, "upgrade9-" + UUID.randomUUID());
        seedPasswordUser(schema, tenantId, "survivor@example.com");

        Flyway toV9 = flyway(schema, "9");
        toV9.migrate();
        assertThat(toV9.info().current().getVersion().getVersion()).isEqualTo("9");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + schema + ".tenant_users WHERE email = 'survivor@example.com'",
                Long.class))
                .isEqualTo(1L);
        assertThat(indexNames(schema)).doesNotContain("uq_tenant_users_global_lower_email");
    }

    private List<String> indexNames(String schema) {
        return jdbcTemplate.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename = 'tenant_users'",
                        schema)
                .stream().map(row -> (String) row.get("indexname")).toList();
    }
}
