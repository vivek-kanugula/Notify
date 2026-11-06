package com.notify.webhook.service;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.delivery.provider.ChannelPreparationException;
import com.notify.demo.config.DemoProperties;
import com.notify.webhook.entity.WebhookEndpointEntity;
import com.notify.webhook.model.WebhookDestination;
import com.notify.webhook.model.WebhookEndpointView;
import com.notify.webhook.model.WebhookUpsertResult;
import com.notify.webhook.repository.WebhookEndpointRepository;
import com.notify.webhook.security.DestinationRejectedException;
import com.notify.webhook.security.WebhookUrlPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant webhook endpoint configuration and destination resolution (/ ).
 *
 * <p>Invariants kept here: the signing secret is generated server-side, never returned by a read, and stored only
 * encrypted (); a URL is accepted only if it passes the same destination policy the sender applies ();
 * {@code is_demo} is derived from the configured demo URL and can never be set by a tenant, so flagging an endpoint
 * is not a way around the policy.
 */
@Service
public class DefaultWebhookEndpointService implements WebhookEndpointService {

    private static final Logger log = LoggerFactory.getLogger(DefaultWebhookEndpointService.class);

    private final WebhookEndpointRepository repository;
    private final WebhookSecretCipher cipher;
    private final WebhookSecretGenerator generator;
    private final WebhookUrlPolicy urlPolicy;
    private final DemoProperties demoProperties;
    private final Clock clock;

    public DefaultWebhookEndpointService(
            WebhookEndpointRepository repository,
            WebhookSecretCipher cipher,
            WebhookSecretGenerator generator,
            WebhookUrlPolicy urlPolicy,
            DemoProperties demoProperties,
            Clock clock
    ) {
        this.repository = repository;
        this.cipher = cipher;
        this.generator = generator;
        this.urlPolicy = urlPolicy;
        this.demoProperties = demoProperties;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WebhookEndpointView> find(UUID tenantId) {
        return repository.findByTenantId(tenantId).map(DefaultWebhookEndpointService::toView);
    }

    /**
     * Creates or updates the endpoint.
     *
     * <p>Concurrency (A2): an update is a column-scoped statement, never a full entity save, so a configuration
     * change and a secret rotation running at the same time cannot undo each other — neither statement mentions the
     * other's column. A first-time create is an {@code ON CONFLICT DO NOTHING} insert: the loser of the race sees a
     * row count of zero, continues as an update, and never reports the secret it generated but did not store.
     */
    @Override
    @Transactional
    public WebhookUpsertResult upsert(UUID tenantId, String url, Boolean enabled) {
        String trimmed = url == null ? null : url.trim();
        boolean demo = isConfiguredDemoUrl(trimmed);
        // Throws DestinationRejectedException, which the controller maps to 422 VALIDATION_FAILED.
        urlPolicy.validate(trimmed, demo);

        Optional<WebhookEndpointEntity> existing = repository.findByTenantId(tenantId);
        if (existing.isPresent()) {
            return update(tenantId, trimmed, demo, enabled, existing.get());
        }

        String secret = generator.generate();
        Instant now = clock.instant();
        int created = repository.insertIfAbsent(UUID.randomUUID(), tenantId, trimmed, demo,
                enabled == null || enabled, cipher.encrypt(tenantId, secret), now);
        if (created == 0) {
            // Another transaction created the row first; its secret is the live one. This call applies only the
            // configuration and reports an update, so no caller is told a secret that is not stored.
            log.info("Concurrent webhook endpoint creation for tenant {}; continuing as an update", tenantId);
            WebhookEndpointEntity winner = repository.findByTenantId(tenantId)
                    .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "No webhook endpoint is configured"));
            return update(tenantId, trimmed, demo, enabled, winner);
        }
        WebhookEndpointEntity saved = repository.findByTenantId(tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "No webhook endpoint is configured"));
        log.info("Webhook endpoint created (tenantId={}, endpointId={}, demo={})", tenantId, saved.getId(), demo);
        return new WebhookUpsertResult(toView(saved), secret, true);
    }

    private WebhookUpsertResult update(
            UUID tenantId, String url, boolean demo, Boolean enabled, WebhookEndpointEntity current) {
        boolean effectiveEnabled = enabled == null ? current.isEnabled() : enabled;
        int updated = repository.updateConfiguration(tenantId, url, demo, effectiveEnabled, clock.instant());
        if (updated != 1) {
            // The row is gone; no accepted error code exists for a write conflict (adds none), and "there is
            // no endpoint" is exactly what the caller now faces.
            throw new NotifyException(ErrorCode.NOT_FOUND, "No webhook endpoint is configured");
        }
        WebhookEndpointEntity reread = repository.findByTenantId(tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "No webhook endpoint is configured"));
        log.info("Webhook endpoint updated (tenantId={}, endpointId={}, demo={}, enabled={})",
                tenantId, reread.getId(), reread.isDemo(), reread.isEnabled());
        return new WebhookUpsertResult(toView(reread), null, false);
    }

    /**
     * Replaces the signing secret and returns it once.
     *
     * <p>The new secret is returned only after a statement that updated exactly one row (A2): if the row vanished or
     * another rotation won, this call fails rather than handing back a secret that is not the live one.
     * Only the secret column is written, so a concurrent URL change survives.
     */
    @Override
    @Transactional
    public String rotateSecret(UUID tenantId) {
        WebhookEndpointEntity entity = repository.findByTenantId(tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "No webhook endpoint is configured"));
        String secret = generator.generate();
        int updated = repository.updateSigningSecret(tenantId, cipher.encrypt(tenantId, secret), clock.instant());
        if (updated != 1) {
            throw new NotifyException(ErrorCode.NOT_FOUND, "No webhook endpoint is configured");
        }
        // Rotation is immediate: in-flight retries are signed with the new secret ().
        log.info("Webhook signing secret rotated (tenantId={}, endpointId={})", tenantId, entity.getId());
        return secret;
    }

    @Override
    @Transactional
    public void delete(UUID tenantId) {
        WebhookEndpointEntity entity = repository.findByTenantId(tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "No webhook endpoint is configured"));
        int deleted = repository.deleteByTenantIdScoped(tenantId);
        if (deleted != 1) {
            throw new NotifyException(ErrorCode.NOT_FOUND, "No webhook endpoint is configured");
        }
        log.info("Webhook endpoint deleted permanently (tenantId={}, endpointId={})", tenantId, entity.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WebhookDestination> resolveDestination(UUID tenantId) {
        return repository.findByTenantId(tenantId).map(entity -> toDestination(tenantId, entity));
    }

    /**
     * Delivery-path resolution. The {@code try} surrounds the <b>database access and nothing else</b>: that is the
     * whole boundary, and it is positional, so no code has to infer from the shape of a cause whether a failure was
     * infrastructure. The mapping — decryption included — runs after it, so a malformed or unauthenticated stored
     * secret keeps the behaviour it has today (a failure of this notification) instead of becoming a worker-level
     * infrastructure failure that would stop the channel's container for every tenant.
     *
     * <p>It deliberately carries <b>no</b> {@code @Transactional}, unlike {@link #resolveDestination(UUID)}. The
     * repository call is transactional on its own, so the transaction begins and ends inside the {@code try} and a
     * connection, statement or commit failure is therefore caught here. An annotation on this method would move the
     * commit into the proxy, outside the {@code try}, where a failure would escape as a bare data-access exception
     * and be mistaken for a provider failure again. The entity holds only scalar columns, so the mapping needs no
     * open session.
     */
    @Override
    public Optional<WebhookDestination> resolveDestinationForDelivery(UUID tenantId) {
        Optional<WebhookEndpointEntity> row;
        try {
            row = repository.findByTenantId(tenantId);
        } catch (RuntimeException e) {
            throw new ChannelPreparationException(
                    "Webhook endpoint lookup failed before any provider request", e);
        }
        return row.map(entity -> toDestination(tenantId, entity));
    }

    private WebhookDestination toDestination(UUID tenantId, WebhookEndpointEntity entity) {
        return new WebhookDestination(
                entity.getId(),
                entity.getTenantId(),
                entity.getUrl(),
                entity.isDemo(),
                entity.isEnabled(),
                cipher.decrypt(tenantId, entity.getSigningSecretEnc()));
    }

    @Override
    @Transactional
    public void provisionDefault(UUID tenantId) {
        if (!demoProperties.provisioningEnabled()) {
            return;
        }
        if (repository.existsByTenantId(tenantId)) {
            return;
        }
        String url = demoProperties.webhookUrl();
        try {
            urlPolicy.validate(url, true);
        } catch (DestinationRejectedException e) {
            // A demo misconfiguration must never fail signup.
            log.warn("Default webhook endpoint not provisioned for tenant {}: configured demo URL rejected ({})",
                    tenantId, e.reason());
            return;
        }
        if (!cipher.available()) {
            log.warn("Default webhook endpoint not provisioned for tenant {}: no webhook secret key is configured", tenantId);
            return;
        }
        Instant now = clock.instant();
        int created = repository.insertIfAbsent(UUID.randomUUID(), tenantId, url, true, true,
                cipher.encrypt(tenantId, generator.generate()), now);
        if (created == 0) {
            // A concurrent signup path or a first PUT already created it; provisioning is best-effort by design.
            log.info("Default webhook endpoint already created concurrently for tenant {}", tenantId);
            return;
        }
        log.info("Default demo webhook endpoint provisioned (tenantId={})", tenantId);
    }

    private boolean isConfiguredDemoUrl(String url) {
        return demoProperties.provisioningEnabled() && demoProperties.webhookUrl().equals(url);
    }

    private static WebhookEndpointView toView(WebhookEndpointEntity entity) {
        return new WebhookEndpointView(
                entity.getId(), entity.getUrl(), entity.isDemo(), entity.isEnabled(),
                entity.isStatusCallbacks(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
