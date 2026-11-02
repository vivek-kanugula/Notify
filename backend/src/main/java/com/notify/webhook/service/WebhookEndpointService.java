package com.notify.webhook.service;

import com.notify.webhook.model.WebhookDestination;
import com.notify.webhook.model.WebhookEndpointView;
import com.notify.webhook.model.WebhookUpsertResult;

import java.util.Optional;
import java.util.UUID;

/**
 * The webhook feature's boundary. Other features (authentication at signup, delivery at send time) depend on this
 * interface and never on the repository or the entity, per the project's feature-boundary rule.
 */
public interface WebhookEndpointService {

    /** Configuration read. Never includes the signing secret. */
    Optional<WebhookEndpointView> find(UUID tenantId);

    /**
     * Creates or updates the tenant's single endpoint. The signing secret is returned only when this call created the
     * endpoint (, ).
     *
     * @param enabled {@code null} leaves the stored value unchanged
     * @throws com.notify.webhook.security.DestinationRejectedException when the URL fails the destination policy
     */
    WebhookUpsertResult upsert(UUID tenantId, String url, Boolean enabled);

    /** Replaces the signing secret and returns the new one once. */
    String rotateSecret(UUID tenantId);

    /**
     * Destination for one delivery attempt, with the decrypted signing secret (the current row, not a
     * snapshot). Empty when the tenant has no endpoint.
     *
     * <p>Used by the request-driven surfaces (the test ping, the demo receiver). A database failure propagates as the
     * data-access exception it is; the delivery worker needs a different boundary and uses
     * {@link #resolveDestinationForDelivery(UUID)} instead.
     */
    Optional<WebhookDestination> resolveDestination(UUID tenantId);

    /**
     * The same destination resolution, for the <b>delivery worker</b> path only.
     *
     * <p>It differs in exactly one respect: a failure of the database access is thrown as
     * {@link com.notify.delivery.provider.ChannelPreparationException}, so the worker classifies and recovers from it
     * under the accepted policy instead of the orchestrator turning it into a provider delivery outcome that
     * consumes the notification's retry budget. Everything else — the row read, the "current row, not a
     * snapshot" rule, and the decryption of the signing secret, including how a decryption failure behaves — is
     * identical to {@link #resolveDestination(UUID)}. Decryption sits deliberately <em>outside</em> that boundary: a
     * malformed or unauthenticated stored secret is a tenant data fault, not infrastructure, and must stay a failure
     * of that one notification rather than stopping the channel's worker.
     */
    Optional<WebhookDestination> resolveDestinationForDelivery(UUID tenantId);

    /**
     * Signup-time provisioning of the default demo endpoint (). Does nothing when no demo URL is configured,
     * when the tenant already has an endpoint, or when the configured demo URL fails the destination policy: signup
     * must never fail because of a demo misconfiguration.
     */
    void provisionDefault(UUID tenantId);
}
