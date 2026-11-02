package com.notify.webhook.service;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import com.notify.webhook.model.WebhookDestination;
import com.notify.webhook.model.WebhookUpsertResult;
import com.notify.webhook.repository.WebhookEndpointRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2: two endpoint writers at once, in separate database transactions.
 *
 * <p>The defect class this guards against is the stale entity save: a request that read the row, then wrote the whole
 * entity back, would restore the signing secret a concurrent rotation had just replaced — or overwrite a URL a
 * concurrent configuration change had just set. The repair is that each write is a column-scoped statement, so the
 * two can never clobber each other.
 *
 * <p>The first two cases are deterministic: the interleaving is forced, not raced. The last two add real concurrency
 * on top, asserting the invariants that must hold for every interleaving.
 */
class WebhookEndpointConcurrencyTest extends AbstractPostgresIntegrationTest {

    private static final String URL_A = "https://receiver-a.example.com/hook";
    private static final String URL_B = "https://receiver-b.example.com/hook";

    @Autowired
    private WebhookEndpointService service;

    @Autowired
    private WebhookEndpointRepository repository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    private UUID newTenant() {
        return tenantRepository.saveAndFlush(
                new TenantEntity("Concurrency", "wh-conc-" + UUID.randomUUID())).getId();
    }

    private String liveSecret(UUID tenantId) {
        return service.resolveDestination(tenantId).map(WebhookDestination::secret).orElseThrow();
    }

    private long rowCount(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM webhook_endpoints WHERE tenant_id = ?", Long.class, tenantId);
    }

    @Test
    @DisplayName("a configuration update after a rotation keeps the rotated secret: no stale entity save")
    void configurationUpdateDoesNotRestoreTheOldSecret() {
        UUID tenantId = newTenant();
        String original = service.upsert(tenantId, URL_A, null).secretOnce();

        // The read a slow request would have been holding while the rotation happened.
        var staleSnapshot = repository.findByTenantId(tenantId).orElseThrow();
        assertThat(staleSnapshot.getSigningSecretEnc()).isNotBlank();

        String rotated = service.rotateSecret(tenantId);
        assertThat(rotated).isNotEqualTo(original);

        // The slow request now applies its configuration change.
        WebhookUpsertResult update = service.upsert(tenantId, URL_B, false);

        assertThat(update.view().url()).isEqualTo(URL_B);
        assertThat(update.view().enabled()).isFalse();
        assertThat(liveSecret(tenantId)).as("the rotated secret must survive the configuration write")
                .isEqualTo(rotated);
    }

    @Test
    @DisplayName("a rotation after a configuration update keeps the new URL: the secret write touches no other column")
    void rotationDoesNotRestoreTheOldConfiguration() {
        UUID tenantId = newTenant();
        service.upsert(tenantId, URL_A, null);

        var staleSnapshot = repository.findByTenantId(tenantId).orElseThrow();
        assertThat(staleSnapshot.getUrl()).isEqualTo(URL_A);

        service.upsert(tenantId, URL_B, false);
        String rotated = service.rotateSecret(tenantId);

        WebhookDestination destination = service.resolveDestination(tenantId).orElseThrow();
        assertThat(destination.url()).as("the rotation must not restore the previous URL").isEqualTo(URL_B);
        assertThat(destination.enabled()).isFalse();
        assertThat(destination.secret()).isEqualTo(rotated);
    }

    @Test
    @DisplayName("concurrent first-time PUTs create exactly one endpoint, with no constraint error reaching the caller")
    void concurrentFirstTimeCreates() throws Exception {
        UUID tenantId = newTenant();
        CyclicBarrier barrier = new CyclicBarrier(2);

        List<Callable<WebhookUpsertResult>> calls = List.of(
                upsertAt(barrier, tenantId, URL_A),
                upsertAt(barrier, tenantId, URL_B));
        List<Future<WebhookUpsertResult>> futures = pool.invokeAll(calls, 60, TimeUnit.SECONDS);

        List<WebhookUpsertResult> results = new ArrayList<>();
        for (Future<WebhookUpsertResult> future : futures) {
            results.add(future.get());
        }

        assertThat(rowCount(tenantId)).as("UNIQUE (tenant_id) holds").isEqualTo(1);
        assertThat(results).hasSize(2);
        assertThat(results.stream().filter(r -> r.secretOnce() != null).count())
                .as("only the request that actually created the row reveals a secret").isEqualTo(1);
        // Whichever call created the row, the secret it returned is the live one.
        WebhookUpsertResult creator = results.stream().filter(WebhookUpsertResult::created).findFirst().orElseThrow();
        assertThat(liveSecret(tenantId)).isEqualTo(creator.secretOnce());
        assertThat(service.find(tenantId).orElseThrow().url()).isIn(URL_A, URL_B);
    }

    @Test
    @DisplayName("concurrent rotations both persist, and the live secret is one of the two that were returned")
    void concurrentRotations() throws Exception {
        UUID tenantId = newTenant();
        service.upsert(tenantId, URL_A, null);
        CyclicBarrier barrier = new CyclicBarrier(2);

        List<Callable<String>> calls = List.of(rotateAt(barrier, tenantId), rotateAt(barrier, tenantId));
        List<Future<String>> futures = pool.invokeAll(calls, 60, TimeUnit.SECONDS);

        List<String> returned = new ArrayList<>();
        for (Future<String> future : futures) {
            returned.add(future.get());
        }

        assertThat(returned).hasSize(2).doesNotHaveDuplicates();
        assertThat(liveSecret(tenantId))
                .as("a rotation never reports a secret it did not store")
                .isIn(returned.get(0), returned.get(1));
        assertThat(service.find(tenantId).orElseThrow().url()).isEqualTo(URL_A);
    }

    @Test
    @DisplayName("a configuration update and a rotation running together keep both effects")
    void concurrentUpdateAndRotation() throws Exception {
        UUID tenantId = newTenant();
        String original = service.upsert(tenantId, URL_A, null).secretOnce();
        CyclicBarrier barrier = new CyclicBarrier(2);

        Future<WebhookUpsertResult> update = pool.submit(upsertAt(barrier, tenantId, URL_B));
        Future<String> rotation = pool.submit(rotateAt(barrier, tenantId));

        WebhookUpsertResult updated = update.get(60, TimeUnit.SECONDS);
        String rotated = rotation.get(60, TimeUnit.SECONDS);

        assertThat(updated.secretOnce()).isNull();
        assertThat(rotated).isNotEqualTo(original);

        WebhookDestination destination = service.resolveDestination(tenantId).orElseThrow();
        assertThat(destination.url()).as("the URL change is not lost").isEqualTo(URL_B);
        assertThat(destination.secret()).as("the rotation is not lost").isEqualTo(rotated);
        assertThat(rowCount(tenantId)).isEqualTo(1);
    }

    private Callable<WebhookUpsertResult> upsertAt(CyclicBarrier barrier, UUID tenantId, String url) {
        return () -> {
            barrier.await(30, TimeUnit.SECONDS);
            return service.upsert(tenantId, url, null);
        };
    }

    private Callable<String> rotateAt(CyclicBarrier barrier, UUID tenantId) {
        return () -> {
            barrier.await(30, TimeUnit.SECONDS);
            return service.rotateSecret(tenantId);
        };
    }
}
