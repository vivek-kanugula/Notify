package com.notify.demo.seed;

import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Seeds realistic local demo data for the {@code acme} and {@code globex} tenants (/ -3).
 *
 * <p><b>Three layers of protection,</b> because this writes tenants, templates and notifications:
 * <ol>
 *   <li>{@code @Profile("local")} — the bean does not exist in any other profile;</li>
 *   <li>a runtime guard that refuses to run if the active profiles contain {@code prod} or {@code test},
 *       covering the case where {@code local} is combined with one of them;</li>
 *   <li>idempotence — if either demo tenant already exists the seeder does nothing, so restarts do not
 *       accumulate data.</li>
 * </ol>
 *
 * <p>The seeded slugs are the ones {@code notify.demo.tenant-slugs} refers to (), so the public demo
 * inbox has something to show locally. Recipients are obviously fake {@code @example.com} addresses and no
 * credential, secret or real personal data is written.
 */
@Component
@Profile("local")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final List<String> FORBIDDEN_PROFILES = List.of("prod", "test");
    private static final String ACME = "acme";
    private static final String GLOBEX = "globex";
    private static final int NOTIFICATIONS_PER_TENANT = 100;

    /** Terminal and in-flight statuses, so local screens show a realistic spread. */
    private static final List<String> STATUS_SPREAD = List.of(
            "DELIVERED", "DELIVERED", "DELIVERED", "DELIVERED", "DELIVERED",
            "FAILED", "CANCELLED", "QUEUED", "RETRYING", "DLQ");

    private final JdbcTemplate jdbc;
    private final TenantRepository tenantRepository;
    private final Environment environment;
    private final Clock clock;

    public DemoDataSeeder(JdbcTemplate jdbc, TenantRepository tenantRepository,
                          Environment environment, Clock clock) {
        this.jdbc = jdbc;
        this.tenantRepository = tenantRepository;
        this.environment = environment;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!safeToSeed()) {
            return;
        }
        if (tenantRepository.findBySlug(ACME).isPresent() || tenantRepository.findBySlug(GLOBEX).isPresent()) {
            log.info("Demo seed: demo tenants already present, nothing to do");
            return;
        }

        seedTenant("Acme Corp", ACME);
        seedTenant("Globex", GLOBEX);
        log.info("Demo seed: created {} and {} with templates, notifications and dead-letter entries",
                ACME, GLOBEX);
    }

    /** Visible for testing: the guard that keeps this away from production and test databases. */
    boolean safeToSeed() {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        List<String> forbidden = active.stream().filter(FORBIDDEN_PROFILES::contains).toList();
        if (!forbidden.isEmpty()) {
            log.warn("Demo seed: refusing to run, active profiles include {} (active: {})", forbidden, active);
            return false;
        }
        return true;
    }

    private void seedTenant(String name, String slug) {
        UUID tenantId = tenantRepository.save(new TenantEntity(name, slug)).getId();

        UUID welcome = seedTemplate(tenantId, "welcome-email", "EMAIL",
                "Welcome to {{company}}", "Hello {{name}}, welcome aboard.");
        seedTemplate(tenantId, "order-shipped", "EMAIL",
                "Your order {{orderId}} has shipped", "Hi {{name}}, your order is on its way.");

        for (int i = 0; i < NOTIFICATIONS_PER_TENANT; i++) {
            String status = STATUS_SPREAD.get(i % STATUS_SPREAD.size());
            UUID notificationId = seedNotification(tenantId, slug, status, i);
            if ("DLQ".equals(status)) {
                seedDeadLetterEntry(tenantId, notificationId);
            }
        }
        log.debug("Demo seed: tenant {} seeded with template {} and {} notifications",
                slug, welcome, NOTIFICATIONS_PER_TENANT);
    }

    private UUID seedTemplate(UUID tenantId, String name, String channel, String subject, String body) {
        UUID templateId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO templates (id, tenant_id, name, channel, description)
                VALUES (?, ?, ?, ?, ?)
                """, templateId, tenantId, name, channel, "Demo template " + name);
        jdbc.update("""
                INSERT INTO template_versions (tenant_id, template_id, version_number, status, subject,
                    body, content_type)
                VALUES (?, ?, 1, 'PUBLISHED', ?, ?, 'TEXT')
                """, tenantId, templateId, subject, body);
        return templateId;
    }

    private UUID seedNotification(UUID tenantId, String slug, String status, int index) {
        UUID id = UUID.randomUUID();
        Instant createdAt = clock.instant().minus(Duration.ofHours(index));
        boolean terminal = List.of("DELIVERED", "FAILED", "CANCELLED", "DLQ").contains(status);
        Timestamp completedAt = terminal
                ? Timestamp.from(createdAt.plus(Duration.ofSeconds(2)))
                : null;

        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body, sender,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, deferral_count,
                    provider, last_error_code, completed_at, created_at, updated_at)
                VALUES (?, ?, 'EMAIL', ?, ?, ?, ?, 'notifications@example.com',
                    1, 1, ?, 5, 0, ?, ?, ?, ?, ?)
                """,
                id, tenantId, status,
                "user" + index + "@example.com",
                "Demo notification " + index + " for " + slug,
                "This is seeded local demo content. Nothing here is real.",
                "DELIVERED".equals(status) ? 1 : 2,
                "DELIVERED".equals(status) ? "fake" : null,
                "FAILED".equals(status) || "DLQ".equals(status) ? "PROVIDER_UNAVAILABLE" : null,
                completedAt,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));
        return id;
    }

    private void seedDeadLetterEntry(UUID tenantId, UUID notificationId) {
        jdbc.update("""
                INSERT INTO dead_letter_entries (tenant_id, notification_id, channel, reason, last_error_code,
                    attempt_count, status)
                VALUES (?, ?, 'EMAIL', 'MAX_RETRIES_EXCEEDED', 'PROVIDER_UNAVAILABLE', 5, 'OPEN')
                """, tenantId, notificationId);
    }
}
