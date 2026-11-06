package com.notify.delivery.provider.webhook;

import com.notify.common.model.Channel;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.provider.DeliveryChannelAdapter;
import com.notify.delivery.service.DeliveryOrchestrator;
import com.notify.delivery.service.TransientDbRetry;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.webhook.config.WebhookProperties;
import com.notify.webhook.model.WebhookDestination;
import com.notify.webhook.security.DestinationRejectedException;
import com.notify.webhook.security.DestinationRejectedException.Reason;
import com.notify.webhook.security.DestinationResolver;
import com.notify.webhook.service.WebhookEndpointService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * WEBHOOK side of the channel seam (/ ). One claimed notification becomes one signed HTTP POST.
 *
 * <p>Order of work, and why: resolve the tenant's current endpoint (, never a snapshot, so a corrected URL heals
 * in-flight retries); refuse immediately and permanently when there is no endpoint, it is disabled, or the policy
 * rejects the destination, because none of those heal by retrying; build bytes that are identical for every attempt
 * of the cycle (); sign those exact bytes (); send with the connection pinned to a validated address
 * (); classify the transport result ().
 *
 * <p>A <em>name-resolution</em> failure is the one destination outcome that is retried: DNS is remote infrastructure
 * that heals, so classifies it as {@code WEBHOOK_UNREACHABLE} like any other unreachable receiver. Only an
 * actual policy violation — a rejected URL or a blocked address — is permanent.
 *
 * <p>No database transaction is open during the send: the endpoint read happens first and commits, and the orchestrator
 * refuses to call an adapter inside a transaction.
 */
@Component
public class WebhookChannelAdapter implements DeliveryChannelAdapter {

    public static final String HEADER_DELIVERY_ID = "X-Notify-Delivery-ID";
    public static final String HEADER_NOTIFICATION_ID = "X-Notify-Notification-ID";
    public static final String HEADER_ATTEMPT = "X-Notify-Attempt";

    public static final String ENDPOINT_MISSING = "WEBHOOK_ENDPOINT_MISSING";
    public static final String ENDPOINT_DISABLED = "WEBHOOK_ENDPOINT_DISABLED";
    public static final String DESTINATION_BLOCKED = "WEBHOOK_DESTINATION_BLOCKED";

    /**
     * Refusals decided before any provider contact. CONTRACTS records them with
     * {@code delivery_attempts.provider = 'notify-system'}, like the tenant gate, because no request was sent.
     */
    private static final Set<String> PRE_SEND_REFUSALS = Set.of(ENDPOINT_MISSING, ENDPOINT_DISABLED, DESTINATION_BLOCKED);

    private static final Logger log = LoggerFactory.getLogger(WebhookChannelAdapter.class);

    private final WebhookEndpointService endpointService;
    private final DestinationResolver destinationResolver;
    private final WebhookPayloadFactory payloadFactory;
    private final WebhookSender sender;
    private final TransientDbRetry dbRetry;
    private final WebhookProperties properties;
    private final Clock clock;

    public WebhookChannelAdapter(
            WebhookEndpointService endpointService,
            DestinationResolver destinationResolver,
            WebhookPayloadFactory payloadFactory,
            WebhookSender sender,
            TransientDbRetry dbRetry,
            WebhookProperties properties,
            Clock clock
    ) {
        this.endpointService = endpointService;
        this.destinationResolver = destinationResolver;
        this.payloadFactory = payloadFactory;
        this.sender = sender;
        this.dbRetry = dbRetry;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Channel channel() {
        return Channel.WEBHOOK;
    }

    @Override
    public String providerName() {
        return WebhookResponseMapper.PROVIDER_NAME;
    }

    @Override
    public String providerFor(DeliveryResult result) {
        String code = switch (result) {
            case DeliveryResult.PermanentFailure p -> p.code();
            case DeliveryResult.RetryableFailure r -> r.code();
            case DeliveryResult.Deferred d -> d.code();
            case DeliveryResult.Success s -> null;
        };
        // A resilience refusal (/ ) belongs to the same group: it is decided before any socket is opened.
        return (code != null && PRE_SEND_REFUSALS.contains(code))
                || com.notify.delivery.resilience.ProviderCallGuard.isLocalRefusal(code)
                ? DeliveryOrchestrator.SYSTEM_PROVIDER : providerName();
    }

    @Override
    public DeliveryResult send(ClaimSnapshot claim) {
        // The whole attempt, name resolution included, runs inside one wall-clock budget ().
        WebhookDeadline deadline = WebhookDeadline.startingNow(properties.totalTimeout());

        // Preparation. The short in-memory retry absorbs a database blip (its own classifier agrees on which
        // categories are worth retrying); if the database is still unavailable afterwards the failure leaves here as
        // a ChannelPreparationException, which the orchestrator rethrows so the worker's policy handles it.
        // It is never turned into a provider outcome: no request has been sent, so there is no provider outcome.
        Optional<WebhookDestination> configured =
                dbRetry.call(() -> endpointService.resolveDestinationForDelivery(claim.tenantId()));
        if (configured.isEmpty()) {
            return new DeliveryResult.PermanentFailure(ENDPOINT_MISSING,
                    "Tenant has no webhook endpoint configured", null, null);
        }
        WebhookDestination destination = configured.get();
        if (!destination.enabled()) {
            return new DeliveryResult.PermanentFailure(ENDPOINT_DISABLED,
                    "Tenant webhook endpoint is disabled", null, null);
        }

        String targetUrl = (claim.recipient() != null && (claim.recipient().startsWith("http://") || claim.recipient().startsWith("https://")))
                ? claim.recipient().trim()
                : destination.url();

        DestinationResolver.PinnedDestination pinned;
        try {
            // Resolution spends the same budget as the rest of the attempt: it gets the configured DNS ceiling or
            // whatever is left, whichever is smaller (F7).
            pinned = destinationResolver.resolve(targetUrl, destination.demo(),
                    deadline.phaseTimeoutMs(properties.dnsTimeout().toMillis()));
        } catch (DestinationRejectedException e) {
            return rejected(e, claim);
        }

        byte[] body = payloadFactory.build(claim, claim.createdAt());
        Instant signedAt = clock.instant();
        String deliveryId = WebhookPayloadFactory.deliveryId(claim);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER_DELIVERY_ID, deliveryId);
        headers.put(HEADER_NOTIFICATION_ID, claim.id().toString());
        headers.put(HEADER_ATTEMPT, Integer.toString(claim.attemptCount()));
        headers.put(WebhookSignature.HEADER, WebhookSignature.header(destination.secret(), signedAt, body));

        WebhookSendOutcome outcome;
        try {
            outcome = sender.send(new WebhookSendRequest(pinned, deliveryId, body, headers), deadline);
        } catch (com.notify.delivery.resilience.ProviderDeferredException e) {
            // A local refusal taken before any socket was opened (/ ). Nothing was sent, so this becomes the
            // attempt-free deferral: the claim's attempt is refunded, a deferral is counted, and no attempt row is
            // written. It is deliberately NOT a provider failure - the provider was never contacted.
            log.info("Webhook attempt deferred locally before any request (notificationId={}, host={}, reason={})",
                    claim.id(), pinned.url().host(), e.code());
            return new DeliveryResult.Deferred(e.code(), e.retryAfter());
        }
        log.info("Webhook attempt finished (notificationId={}, attempt={}, host={}, status={}, failure={}, latencyMs={})",
                claim.id(), claim.attemptCount(), pinned.url().host(), outcome.httpStatus(), outcome.failure(),
                outcome.latencyMs());
        return WebhookResponseMapper.map(outcome);
    }

    /**
     * A name-resolution failure is retryable; every other rejection is a policy violation and permanent. The reason is
     * logged, never the resolved address.
     */
    private DeliveryResult rejected(DestinationRejectedException e, ClaimSnapshot claim) {
        if (e.reason() == Reason.DNS_FAILURE) {
            log.warn("Webhook destination could not be resolved for tenant {} (notificationId={}): {}",
                    claim.tenantId(), claim.id(), e.reason());
            return new DeliveryResult.RetryableFailure("WEBHOOK_UNREACHABLE", e.getMessage(), null, null);
        }
        log.warn("Webhook destination rejected for tenant {} (notificationId={}): {}",
                claim.tenantId(), claim.id(), e.reason());
        return new DeliveryResult.PermanentFailure(DESTINATION_BLOCKED, e.getMessage(), null, null);
    }
}
