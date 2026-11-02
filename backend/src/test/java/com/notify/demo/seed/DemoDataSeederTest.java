package com.notify.demo.seed;

import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * / -3: the demo seeder's guards. {@code @Profile("local")} already keeps the bean out of other
 * profiles, so what is tested here is the second line of defence — the runtime refusal when {@code local} is
 * combined with {@code prod} or {@code test} — and idempotence.
 */
class DemoDataSeederTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"), ZoneOffset.UTC);

    private DemoDataSeeder seeder(JdbcTemplate jdbc, TenantRepository tenants, String... profiles) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(profiles);
        return new DemoDataSeeder(jdbc, tenants, environment, clock);
    }

    @Test
    @DisplayName("refuses to seed when the active profiles include prod")
    void refusesUnderProd() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TenantRepository tenants = mock(TenantRepository.class);

        DemoDataSeeder seeder = seeder(jdbc, tenants, "local", "prod");

        assertThat(seeder.safeToSeed()).isFalse();
        seeder.run(new DefaultApplicationArguments());
        verifyNoInteractions(jdbc);
        verifyNoInteractions(tenants);
    }

    @Test
    @DisplayName("refuses to seed when the active profiles include test")
    void refusesUnderTest() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TenantRepository tenants = mock(TenantRepository.class);

        DemoDataSeeder seeder = seeder(jdbc, tenants, "local", "test");

        assertThat(seeder.safeToSeed()).isFalse();
        seeder.run(new DefaultApplicationArguments());
        verifyNoInteractions(jdbc);
        verifyNoInteractions(tenants);
    }

    @Test
    @DisplayName("permits seeding under local alone")
    void permitsUnderLocal() {
        DemoDataSeeder seeder = seeder(mock(JdbcTemplate.class), mock(TenantRepository.class), "local");
        assertThat(seeder.safeToSeed()).isTrue();
    }

    @Test
    @DisplayName("is idempotent: an existing demo tenant means nothing is written")
    void idempotentWhenDemoTenantsExist() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TenantRepository tenants = mock(TenantRepository.class);
        when(tenants.findBySlug("acme"))
                .thenReturn(Optional.of(new com.notify.tenant.entity.TenantEntity("Acme Corp", "acme")));

        seeder(jdbc, tenants, "local").run(new DefaultApplicationArguments());

        verify(tenants, never()).save(any());
        verify(jdbc, never()).update(anyString(), (Object[]) any());
    }
}
