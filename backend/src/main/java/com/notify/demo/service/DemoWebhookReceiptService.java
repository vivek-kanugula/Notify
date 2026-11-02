package com.notify.demo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.delivery.provider.webhook.WebhookChannelAdapter;
import com.notify.delivery.provider.webhook.WebhookSignature;
import com.notify.demo.entity.DemoWebhookReceiptEntity;
import com.notify.demo.repository.DemoWebhookReceiptRepository;
import com.notify.notification.service.NotificationService;
import com.notify.webhook.model.WebhookDestination;
import com.notify.webhook.service.WebhookEndpointService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The demo receiver's own verification and storage ().
 *
 * <p>It is the receiver half of the signature contract, written the way a tenant's own receiver would be: identify the
 * sending tenant from the delivery id, fetch that tenant's secret, recompute the HMAC over the exact bytes received,
 * and compare in constant time. Nothing is stored unless that comparison succeeds, so the table cannot be filled by
 * anyone who does not hold the secret.
 *
 * <p>It stores only an allowlisted header subset. An {@code Authorization} header, a cookie or any other header a
 * future client might send is never persisted.
 */
@Service
@Profile("!prod")
public class DemoWebhookReceiptService {

    /** Only these headers are ever stored; everything else is dropped (). */
    static final java.util.List<String> STORED_HEADERS = java.util.List.of(
            WebhookChannelAdapter.HEADER_DELIVERY_ID,
            WebhookChannelAdapter.HEADER_ATTEMPT,
            "Content-Type",
            "User-Agent");

    private static final Logger log = LoggerFactory.getLogger(DemoWebhookReceiptService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PING_PREFIX = "ping-";

    private final DemoWebhookReceiptRepository repository;
    private final WebhookEndpointService endpointService;
    private final NotificationService notificationService;
    private final Clock clock;

    public DemoWebhookReceiptService(
            DemoWebhookReceiptRepository repository,
            WebhookEndpointService endpointService,
            NotificationService notificationService,
            Clock clock
    ) {
        this.repository = repository;
        this.endpointService = endpointService;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    /** Why a delivery was not accepted. The receiver answers 401 or 404; it never says which rule failed in detail. */
    public enum Rejection {
        /** No or malformed {@code X-Notify-Delivery-ID}. */
        BAD_DELIVERY_ID,
        /** The delivery id names no notification, or the ping names no tenant. */
        UNKNOWN_SENDER,
        /** The tenant has no webhook endpoint, or it is not a demo endpoint. */
        NOT_A_DEMO_ENDPOINT,
        /** Missing, malformed or wrong signature. */
        BAD_SIGNATURE
    }

    /** Accepted: one receipt row was stored. Rejected: nothing was stored. */
    public sealed interface Receipt {
        record Accepted(UUID receiptId, UUID tenantId, UUID notificationId, boolean ping) implements Receipt {
        }

        record Rejected(Rejection rejection) implements Receipt {
        }
    }

    @Transactional
    public Receipt accept(String deliveryId, String signatureHeader, String tenantHeader, byte[] body,
                          Map<String, String> headers) {
        if (deliveryId == null || deliveryId.isBlank()) {
            return new Receipt.Rejected(Rejection.BAD_DELIVERY_ID);
        }
        String trimmedId = deliveryId.trim();
        boolean ping = trimmedId.startsWith(PING_PREFIX);

        Optional<UUID> notificationId = ping ? Optional.empty() : parseNotificationId(trimmedId);
        if (!ping && notificationId.isEmpty()) {
            return new Receipt.Rejected(Rejection.BAD_DELIVERY_ID);
        }

        Optional<UUID> tenantId = ping
                ? parseUuid(tenantHeader)
                : notificationService.findOwningTenantId(notificationId.orElseThrow());
        if (tenantId.isEmpty()) {
            return new Receipt.Rejected(Rejection.UNKNOWN_SENDER);
        }

        Optional<WebhookDestination> destination = endpointService.resolveDestination(tenantId.get());
        if (destination.isEmpty() || !destination.get().demo()) {
            // A tenant pointing its own, non-demo endpoint here cannot use the shared receiver as storage.
            return new Receipt.Rejected(Rejection.NOT_A_DEMO_ENDPOINT);
        }

        WebhookSignature.Parsed parsed = WebhookSignature.parse(signatureHeader);
        if (parsed == null) {
            return new Receipt.Rejected(Rejection.BAD_SIGNATURE);
        }
        String expected = WebhookSignature.hex(destination.get().secret(), parsed.timestamp(), body);
        if (!WebhookSignature.matches(expected, parsed.v1())) {
            log.warn("Demo receiver rejected a delivery with an invalid signature (tenantId={})", tenantId.get());
            return new Receipt.Rejected(Rejection.BAD_SIGNATURE);
        }

        DemoWebhookReceiptEntity entity = new DemoWebhookReceiptEntity();
        entity.setTenantId(tenantId.get());
        entity.setNotificationId(notificationId.orElse(null));
        entity.setSignatureValid(true);
        entity.setHeaders(storedHeaders(headers));
        entity.setPayload(storedPayload(body));
        entity.setReceivedAt(clock.instant());
        DemoWebhookReceiptEntity saved = repository.save(entity);

        // One row per accepted delivery: a duplicate delivery is stored again, so at-least-once stays visible.
        log.info("Demo receiver stored a verified receipt (tenantId={}, notificationId={}, ping={})",
                tenantId.get(), notificationId.orElse(null), ping);
        return new Receipt.Accepted(saved.getId(), tenantId.get(), notificationId.orElse(null), ping);
    }

    /** {@code <notificationId>-<deliveryCycle>}: everything before the last hyphen is the notification id. */
    private static Optional<UUID> parseNotificationId(String deliveryId) {
        int lastHyphen = deliveryId.lastIndexOf('-');
        if (lastHyphen <= 0) {
            return Optional.empty();
        }
        return parseUuid(deliveryId.substring(0, lastHyphen));
    }

    private static Optional<UUID> parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String storedHeaders(Map<String, String> headers) {
        Map<String, String> allowed = new LinkedHashMap<>();
        if (headers != null) {
            for (String name : STORED_HEADERS) {
                headers.entrySet().stream()
                        .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                        .findFirst()
                        .ifPresent(entry -> allowed.put(name, entry.getValue()));
            }
        }
        try {
            return MAPPER.writeValueAsString(allowed);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /** The column is {@code jsonb}: a body that is not JSON is wrapped rather than rejected or stored raw. */
    private static String storedPayload(byte[] body) {
        String text = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        try {
            MAPPER.readTree(text);
            return text.isBlank() ? "{}" : text;
        } catch (JsonProcessingException e) {
            try {
                return MAPPER.writeValueAsString(Map.of("raw", text));
            } catch (JsonProcessingException ignored) {
                return "{}";
            }
        }
    }
}
