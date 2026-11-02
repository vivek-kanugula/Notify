package com.notify.demo.controller;

import com.notify.delivery.provider.webhook.WebhookChannelAdapter;
import com.notify.delivery.provider.webhook.WebhookSignature;
import com.notify.demo.config.DemoWebhookProperties;
import com.notify.demo.service.DemoWebhookReceiptService;
import com.notify.demo.service.DemoWebhookReceiptService.Receipt;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The shared demo webhook receiver (): the destination a new tenant's default endpoint points at, and the
 * receiver every journey verifies against.
 *
 * <p>It is a public route with no credential, and is protected by the signature instead: a request is accepted only
 * when its HMAC verifies against the sending tenant's secret and that tenant's endpoint is the demo endpoint.
 *
 * <p><b>Every rejection looks the same from outside</b> (F10): one status, one body, for an unparsable delivery id, an
 * unknown delivery, a tenant whose endpoint is not the demo endpoint, and a bad signature alike. An earlier version
 * answered 401 only after the delivery id had resolved to a demo tenant, which told an unauthenticated caller that a
 * notification id existed. The reason stays in the server log.
 *
 * <p><b>Absent under {@code prod}</b> (F3): the bean is {@code @Profile("!prod")}, so the route itself does not exist
 * there — not merely disabled by a property. The earlier build registered it in every profile and relied on the
 * startup guards, which only refused the demo URL, the allowlist and the chaos flag.
 *
 * <p>Known limitation, accepted for no rate limit of its own (non-production surface only).
 */
@RestController
@RequestMapping("/demo/webhook-receiver")
@Profile("!prod")
public class DemoWebhookReceiverController {

    private static final Logger log = LoggerFactory.getLogger(DemoWebhookReceiverController.class);
    private static final String TENANT_HEADER = "X-Notify-Tenant";

    private final DemoWebhookReceiptService receiptService;
    private final DemoWebhookProperties properties;

    public DemoWebhookReceiverController(DemoWebhookReceiptService receiptService, DemoWebhookProperties properties) {
        this.receiptService = receiptService;
        this.properties = properties;
    }

    @PostMapping(consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(value = WebhookChannelAdapter.HEADER_DELIVERY_ID, required = false) String deliveryId,
            @RequestHeader(value = WebhookSignature.HEADER, required = false) String signature,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request
    ) {
        Receipt receipt = receiptService.accept(
                deliveryId, signature, tenantHeader, body == null ? new byte[0] : body, headersOf(request));

        if (receipt instanceof Receipt.Accepted accepted) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("received", true);
            payload.put("receiptId", accepted.receiptId());
            payload.put("signatureValid", true);
            return ResponseEntity.ok(payload);
        }

        DemoWebhookReceiptService.Rejection rejection = ((Receipt.Rejected) receipt).rejection();
        // One response for every rejection reason. The reason is logged by the service, never returned, so the
        // caller cannot tell "no such delivery" from "wrong signature" and cannot probe for notification ids.
        log.info("Demo receiver rejected a delivery ({})", rejection);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("received", false, "signatureValid", false));
    }

    /**
     * Failure injection for retry and classification journeys. Present only when
     * {@code notify.demo.receiver.chaos-enabled} is set, which the {@code prod} profile refuses outright.
     */
    @PostMapping("/fail")
    public ResponseEntity<Map<String, Object>> fail(
            @RequestParam(value = "mode", defaultValue = "500") String mode
    ) throws InterruptedException {
        if (!properties.chaosEnabled()) {
            return ResponseEntity.notFound().build();
        }
        if ("timeout".equalsIgnoreCase(mode)) {
            log.info("Demo receiver chaos: holding the request open");
            // Longer than any configured total timeout, so the sender's own budget ends the attempt.
            Thread.sleep(30_000);
            return ResponseEntity.ok(Map.of("received", true));
        }
        log.info("Demo receiver chaos: answering 500");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("received", false));
    }

    private static Map<String, String> headersOf(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }
}
