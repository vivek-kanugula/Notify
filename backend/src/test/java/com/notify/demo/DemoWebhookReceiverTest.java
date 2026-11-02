package com.notify.demo;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.delivery.provider.webhook.WebhookChannelAdapter;
import com.notify.delivery.provider.webhook.WebhookSignature;
import com.notify.demo.entity.DemoWebhookReceiptEntity;
import com.notify.demo.repository.DemoWebhookReceiptRepository;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import com.notify.webhook.service.WebhookEndpointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * . The receiver has no credential, so the signature is the whole access control: these assertions are mostly
 * about what is <em>not</em> stored.
 */
@TestPropertySource(properties = {
        "notify.demo.webhook-url=http://localhost:9099/demo/webhook-receiver",
        "notify.webhook.demo-allowlist=localhost,127.0.0.1"
})
class DemoWebhookReceiverTest extends AbstractPostgresIntegrationTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private static final String PATH = "/demo/webhook-receiver";
    private static final String DEMO_URL = "http://localhost:9099/demo/webhook-receiver";
    private static final byte[] BODY = "{\"schemaVersion\":1,\"deliveryId\":\"d-1\"}".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private WebhookEndpointService endpointService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private DemoWebhookReceiptRepository receiptRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private UUID newTenant() {
        return tenantRepository.saveAndFlush(
                new TenantEntity("Demo Receiver", "demo-rx-" + UUID.randomUUID())).getId();
    }

    /** A tenant whose endpoint is the demo endpoint, plus the secret its deliveries are signed with. */
    private record DemoTenant(UUID tenantId, String secret) {
    }

    private DemoTenant demoTenant() {
        UUID tenantId = newTenant();
        endpointService.provisionDefault(tenantId);
        return new DemoTenant(tenantId, endpointService.resolveDestination(tenantId).orElseThrow().secret());
    }

    private UUID insertNotification(UUID tenantId) {
        UUID notificationId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO notifications (
                    id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, max_attempts, attempt_count,
                    deferral_count, version, created_at, updated_at
                ) VALUES (?, ?, 'WEBHOOK', 'QUEUED', ?, NULL, 'Body',
                          0, 1, 5, 0, 0, 0, NOW(), NOW())
                """, notificationId, tenantId, DEMO_URL);
        return notificationId;
    }

    private String signature(String secret, byte[] body) {
        return WebhookSignature.header(secret, Instant.now(), body);
    }

    @Test
    @DisplayName("a correctly signed delivery is accepted and stored once, with signature_valid true")
    void verifiedDeliveryIsStored() throws Exception {
        DemoTenant tenant = demoTenant();
        UUID notificationId = insertNotification(tenant.tenantId());
        String deliveryId = notificationId + "-1";

        mockMvc.perform(post(PATH)
                        .header(WebhookChannelAdapter.HEADER_DELIVERY_ID, deliveryId)
                        .header(WebhookChannelAdapter.HEADER_ATTEMPT, "1")
                        .header(WebhookSignature.HEADER, signature(tenant.secret(), BODY))
                        .header("User-Agent", "Notify-Webhook/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.signatureValid").value(true));

        List<DemoWebhookReceiptEntity> receipts =
                receiptRepository.findByNotificationIdOrderByReceivedAtAsc(notificationId);
        assertThat(receipts).hasSize(1);
        DemoWebhookReceiptEntity receipt = receipts.get(0);
        assertThat(receipt.getTenantId()).isEqualTo(tenant.tenantId());
        assertThat(receipt.isSignatureValid()).isTrue();
        // jsonb normalizes key order and spacing, so the stored payload is compared as JSON, not as text.
        assertThat(MAPPER.readTree(receipt.getPayload()).get("deliveryId").textValue()).isEqualTo("d-1");
        assertThat(MAPPER.readTree(receipt.getPayload()).get("schemaVersion").intValue()).isEqualTo(1);
        assertThat(receipt.getReceivedAt()).isNotNull();
    }

    @Test
    @DisplayName("an invalid signature is rejected with the uniform response and stores nothing")
    void invalidSignatureStoresNothing() throws Exception {
        DemoTenant tenant = demoTenant();
        UUID notificationId = insertNotification(tenant.tenantId());
        String wrongSecret = "whsec_" + "A".repeat(43);

        mockMvc.perform(post(PATH)
                        .header(WebhookChannelAdapter.HEADER_DELIVERY_ID, notificationId + "-1")
                        .header(WebhookSignature.HEADER, signature(wrongSecret, BODY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.signatureValid").value(false));

        assertThat(receiptRepository.countByNotificationId(notificationId)).isZero();
    }

    @Test
    @DisplayName("a body changed after signing is rejected: the signature covers the bytes, not just the headers")
    void tamperedBodyIsRejected() throws Exception {
        DemoTenant tenant = demoTenant();
        UUID notificationId = insertNotification(tenant.tenantId());
        String header = signature(tenant.secret(), BODY);

        mockMvc.perform(post(PATH)
                        .header(WebhookChannelAdapter.HEADER_DELIVERY_ID, notificationId + "-1")
                        .header(WebhookSignature.HEADER, header)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":1,\"deliveryId\":\"d-2\"}"))
                .andExpect(status().isNotFound());

        assertThat(receiptRepository.countByNotificationId(notificationId)).isZero();
    }

    @Test
    @DisplayName("a reused signature with a different timestamp is rejected, because t is covered by the HMAC")
    void timestampIsCovered() throws Exception {
        DemoTenant tenant = demoTenant();
        UUID notificationId = insertNotification(tenant.tenantId());
        WebhookSignature.Parsed parsed = WebhookSignature.parse(signature(tenant.secret(), BODY));

        mockMvc.perform(post(PATH)
                        .header(WebhookChannelAdapter.HEADER_DELIVERY_ID, notificationId + "-1")
                        .header(WebhookSignature.HEADER, "t=" + (parsed.timestamp() + 60) + ",v1=" + parsed.v1())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound());

        assertThat(receiptRepository.countByNotificationId(notificationId)).isZero();
    }

    @Test
    @DisplayName("a missing or malformed signature header is rejected and stores nothing")
    void missingSignatureIsRejected() throws Exception {
        DemoTenant tenant = demoTenant();
        UUID notificationId = insertNotification(tenant.tenantId());

        mockMvc.perform(post(PATH)
                        .header(WebhookChannelAdapter.HEADER_DELIVERY_ID, notificationId + "-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(PATH)
                        .header(WebhookChannelAdapter.HEADER_DELIVERY_ID, notificationId + "-1")
                        .header(WebhookSignature.HEADER, "not-a-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound());

        assertThat(receiptRepository.countByNotificationId(notificationId)).isZero();
    }

    @Test
    @DisplayName("a tenant whose endpoint is its own, not the demo endpoint, cannot store receipts here")
    void nonDemoEndpointIsRejected() throws Exception {
        UUID tenantId = newTenant();
        String secret = endpointService.upsert(tenantId, "https://receiver.example.com/hook", null).secretOnce();
        UUID notificationId = insertNotification(tenantId);

        mockMvc.perform(post(PATH)
                        .header(WebhookChannelAdapter.HEADER_DELIVERY_ID, notificationId + "-1")
                        .header(WebhookSignature.HEADER, signature(secret, BODY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound());

        assertThat(receiptRepository.countByNotificationId(notificationId)).isZero();
    }

    @Test
    @DisplayName("an unknown or malformed delivery id is rejected and stores nothing")
    void unknownDeliveryIdIsRejected() throws Exception {
        DemoTenant tenant = demoTenant();

        mockMvc.perform(post(PATH)
                        .header(WebhookChannelAdapter.HEADER_DELIVERY_ID, UUID.randomUUID() + "-1")
                        .header(WebhookSignature.HEADER, signature(tenant.secret(), BODY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(PATH)
                        .header(WebhookChannelAdapter.HEADER_DELIVERY_ID, "not-a-delivery-id")
                        .header(WebhookSignature.HEADER, signature(tenant.secret(), BODY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(PATH)
                        .header(WebhookSignature.HEADER, signature(tenant.secret(), BODY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound());

        assertThat(receiptRepository.findByTenantIdOrderByReceivedAtDesc(tenant.tenantId())).isEmpty();
    }

    @Test
    @DisplayName("a duplicate delivery stores a second row, so at-least-once stays visible to the operator")
    void duplicateDeliveryStoresASecondRow() throws Exception {
        DemoTenant tenant = demoTenant();
        UUID notificationId = insertNotification(tenant.tenantId());
        String deliveryId = notificationId + "-1";
        String header = signature(tenant.secret(), BODY);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(PATH)
                            .header(WebhookChannelAdapter.HEADER_DELIVERY_ID, deliveryId)
                            .header(WebhookChannelAdapter.HEADER_ATTEMPT, Integer.toString(i + 1))
                            .header(WebhookSignature.HEADER, header)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isOk());
        }

        assertThat(receiptRepository.countByNotificationId(notificationId)).isEqualTo(2);
    }

    @Test
    @DisplayName("only allowlisted headers are stored: an Authorization header or a cookie is never persisted")
    void headersAreAllowlisted() throws Exception {
        DemoTenant tenant = demoTenant();
        UUID notificationId = insertNotification(tenant.tenantId());

        mockMvc.perform(post(PATH)
                        .header(WebhookChannelAdapter.HEADER_DELIVERY_ID, notificationId + "-1")
                        .header(WebhookChannelAdapter.HEADER_ATTEMPT, "3")
                        .header(WebhookSignature.HEADER, signature(tenant.secret(), BODY))
                        .header("Authorization", "Bearer super-secret-token")
                        .header("Cookie", "session=abc123")
                        .header("User-Agent", "Notify-Webhook/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());

        String headers = receiptRepository.findByNotificationIdOrderByReceivedAtAsc(notificationId).get(0).getHeaders();
        com.fasterxml.jackson.databind.JsonNode stored = MAPPER.readTree(headers);
        List<String> names = new java.util.ArrayList<>();
        stored.fieldNames().forEachRemaining(names::add);

        assertThat(names).containsExactlyInAnyOrder(
                WebhookChannelAdapter.HEADER_DELIVERY_ID, "X-Notify-Attempt", "Content-Type", "User-Agent");
        assertThat(stored.get("X-Notify-Attempt").textValue()).isEqualTo("3");
        assertThat(headers)
                .doesNotContain("super-secret-token")
                .doesNotContain("session=abc123")
                .doesNotContain(WebhookSignature.HEADER);
    }

    @Test
    @DisplayName("a test ping is accepted with a null notification id, resolved from the tenant header")
    void pingIsAccepted() throws Exception {
        DemoTenant tenant = demoTenant();
        byte[] pingBody = "{\"schemaVersion\":1,\"type\":\"ping\"}".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post(PATH)
                        .header(WebhookChannelAdapter.HEADER_DELIVERY_ID, "ping-" + UUID.randomUUID())
                        .header("X-Notify-Tenant", tenant.tenantId().toString())
                        .header(WebhookSignature.HEADER, signature(tenant.secret(), pingBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pingBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        List<DemoWebhookReceiptEntity> receipts =
                receiptRepository.findByTenantIdOrderByReceivedAtDesc(tenant.tenantId());
        assertThat(receipts).hasSize(1);
        assertThat(receipts.get(0).getNotificationId()).isNull();
        assertThat(receipts.get(0).isSignatureValid()).isTrue();
    }

    @Test
    @DisplayName("a ping without a resolvable tenant header is rejected and stores nothing")
    void pingWithoutTenantIsRejected() throws Exception {
        DemoTenant tenant = demoTenant();
        byte[] pingBody = "{\"type\":\"ping\"}".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post(PATH)
                        .header(WebhookChannelAdapter.HEADER_DELIVERY_ID, "ping-" + UUID.randomUUID())
                        .header(WebhookSignature.HEADER, signature(tenant.secret(), pingBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pingBody))
                .andExpect(status().isNotFound());

        assertThat(receiptRepository.findByTenantIdOrderByReceivedAtDesc(tenant.tenantId())).isEmpty();
    }

    @Test
    @DisplayName("another tenant's secret cannot sign a delivery for this tenant's notification")
    void crossTenantSignatureIsRejected() throws Exception {
        DemoTenant owner = demoTenant();
        DemoTenant other = demoTenant();
        UUID notificationId = insertNotification(owner.tenantId());

        mockMvc.perform(post(PATH)
                        .header(WebhookChannelAdapter.HEADER_DELIVERY_ID, notificationId + "-1")
                        .header(WebhookSignature.HEADER, signature(other.secret(), BODY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound());

        assertThat(receiptRepository.countByNotificationId(notificationId)).isZero();
    }

    @Test
    @DisplayName("the chaos routes are absent unless they are switched on")
    void chaosIsOffByDefault() throws Exception {
        mockMvc.perform(post(PATH + "/fail?mode=500")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("every rejection is indistinguishable from outside: same status, same body (F10)")
    void rejectionsAreIndistinguishable() throws Exception {
        DemoTenant demo = demoTenant();
        UUID notificationId = insertNotification(demo.tenantId());

        UUID ownTenant = newTenant();
        String ownSecret = endpointService.upsert(ownTenant, "https://receiver.example.com/hook", null).secretOnce();
        UUID ownNotification = insertNotification(ownTenant);

        // One request per rejection reason the service distinguishes internally.
        var requests = List.of(
                // BAD_DELIVERY_ID
                post(PATH).header(WebhookSignature.HEADER, signature(demo.secret(), BODY))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY),
                // UNKNOWN_SENDER
                post(PATH).header(WebhookChannelAdapter.HEADER_DELIVERY_ID, UUID.randomUUID() + "-1")
                        .header(WebhookSignature.HEADER, signature(demo.secret(), BODY))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY),
                // NOT_A_DEMO_ENDPOINT
                post(PATH).header(WebhookChannelAdapter.HEADER_DELIVERY_ID, ownNotification + "-1")
                        .header(WebhookSignature.HEADER, signature(ownSecret, BODY))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY),
                // BAD_SIGNATURE on a real, demo-owned delivery — previously the only 401, which revealed that the
                // notification existed and belonged to a demo tenant.
                post(PATH).header(WebhookChannelAdapter.HEADER_DELIVERY_ID, notificationId + "-1")
                        .header(WebhookSignature.HEADER, signature("whsec_" + "Z".repeat(43), BODY))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY));

        String firstBody = null;
        for (var request : requests) {
            var response = mockMvc.perform(request).andExpect(status().isNotFound()).andReturn().getResponse();
            String body = response.getContentAsString();
            if (firstBody == null) {
                firstBody = body;
            } else {
                assertThat(body).isEqualTo(firstBody);
            }
        }

        assertThat(receiptRepository.countByNotificationId(notificationId)).isZero();
        assertThat(receiptRepository.countByNotificationId(ownNotification)).isZero();
    }
}
