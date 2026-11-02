package com.notify.delivery.provider.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.delivery.model.DeliveryResult;
import com.notify.webhook.config.WebhookProperties;
import com.notify.webhook.model.WebhookDestination;
import com.notify.webhook.security.DestinationRejectedException;
import com.notify.webhook.security.DestinationResolver;
import com.notify.webhook.service.WebhookEndpointService;
import com.notify.webhook.service.WebhookPingSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One signed test ping (). It reuses the delivery destination policy, signature and sender, so a successful
 * ping is evidence about the real delivery path and not about a second code path.
 *
 * <p>It writes nothing: no notification, no attempt row, no outbox event. Its delivery id is {@code ping-<uuid>}, so a
 * receiver can tell it apart from a real delivery, and it carries {@code X-Notify-Tenant} because no notification
 * exists to resolve the tenant from.
 */
@Service
public class WebhookPingService implements WebhookPingSender {

    public static final String HEADER_TENANT = "X-Notify-Tenant";
    public static final String PING_PREFIX = "ping-";

    private static final Logger log = LoggerFactory.getLogger(WebhookPingService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebhookEndpointService endpointService;
    private final DestinationResolver destinationResolver;
    private final WebhookSender sender;
    private final WebhookProperties properties;
    private final Clock clock;

    public WebhookPingService(
            WebhookEndpointService endpointService,
            DestinationResolver destinationResolver,
            WebhookSender sender,
            WebhookProperties properties,
            Clock clock
    ) {
        this.endpointService = endpointService;
        this.destinationResolver = destinationResolver;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public PingResult sendTestPing(UUID tenantId) {
        // One budget for the whole ping, resolution included, exactly as a real attempt ().
        WebhookDeadline deadline = WebhookDeadline.startingNow(properties.totalTimeout());

        WebhookDestination destination = endpointService.resolveDestination(tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "No webhook endpoint is configured"));
        if (!destination.enabled()) {
            return new PingResult(false, null, "PERMANENT", WebhookChannelAdapter.ENDPOINT_DISABLED,
                    "The endpoint is disabled; enable it before testing");
        }

        DestinationResolver.PinnedDestination pinned;
        try {
            pinned = destinationResolver.resolve(destination.url(), destination.demo(),
                    deadline.phaseTimeoutMs(properties.dnsTimeout().toMillis()));
        } catch (DestinationRejectedException e) {
            // The stored URL passed the policy when it was saved; DNS or the profile may have changed since. A name
            // resolution failure is retryable, like any unreachable receiver (); a policy violation is not.
            if (e.reason() == DestinationRejectedException.Reason.DNS_FAILURE) {
                return new PingResult(false, null, "RETRYABLE", "WEBHOOK_UNREACHABLE", e.getMessage());
            }
            return new PingResult(false, null, "PERMANENT", WebhookChannelAdapter.DESTINATION_BLOCKED, e.getMessage());
        }

        Instant now = clock.instant();
        String deliveryId = PING_PREFIX + UUID.randomUUID();
        byte[] body = pingBody(deliveryId, now);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(WebhookChannelAdapter.HEADER_DELIVERY_ID, deliveryId);
        headers.put(WebhookChannelAdapter.HEADER_ATTEMPT, "1");
        headers.put(HEADER_TENANT, tenantId.toString());
        headers.put(WebhookSignature.HEADER, WebhookSignature.header(destination.secret(), now, body));

        WebhookSendOutcome outcome = sender.send(new WebhookSendRequest(pinned, deliveryId, body, headers), deadline);
        log.info("Webhook test ping sent (tenantId={}, host={}, status={}, failure={})",
                tenantId, pinned.url().host(), outcome.httpStatus(), outcome.failure());
        return toResult(outcome);
    }

    private static byte[] pingBody(String deliveryId, Instant now) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", WebhookPayloadFactory.SCHEMA_VERSION);
        root.put("deliveryId", deliveryId);
        root.putNull("notificationId");
        root.put("channel", "WEBHOOK");
        root.putNull("recipient");
        root.put("subject", "Notify webhook test");
        root.put("body", "This is a test delivery from Notify. No notification was created.");
        root.putNull("metadata");
        root.put("createdAt", now.toString());
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static PingResult toResult(WebhookSendOutcome outcome) {
        DeliveryResult mapped = WebhookResponseMapper.map(outcome);
        return switch (mapped) {
            case DeliveryResult.Success s ->
                    new PingResult(true, outcome.httpStatus(), "DELIVERED", null, "Receiver answered 2xx");
            case DeliveryResult.RetryableFailure r ->
                    new PingResult(false, outcome.httpStatus(), "RETRYABLE", r.code(), r.message());
            case DeliveryResult.PermanentFailure p ->
                    new PingResult(false, outcome.httpStatus(), "PERMANENT", p.code(), p.message());
            case DeliveryResult.Deferred d ->
                    new PingResult(false, outcome.httpStatus(), "RETRYABLE", d.code(), "Deferred");
        };
    }
}
