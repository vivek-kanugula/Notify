package com.notify.dlq.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.auth.dto.LoginRequest;
import com.notify.auth.dto.SignupRequest;
import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.dlq.dto.DeadLetterEventEnvelope;
import com.notify.dlq.repository.DeadLetterEntryRepository;
import com.notify.dlq.service.DeadLetterMaterializationService;
import com.notify.dlq.service.DeadLetterMaterializationService.RecordCoordinates;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin DLQ HTTP surface (/ , ; to , , ).
 *
 * <p>Authorization first: the chain requires {@code ROLE_PLATFORM_ADMIN} and the handler re-checks the
 * {@code notify-admin} audience, so a tenant-owner JWT and an API key are both rejected before any handler runs.
 * Then the projection: no recipient, subject, body, URL, secret, snippet or attempt error message may appear.
 */
class AdminDlqApiTest extends AbstractPostgresIntegrationTest {

    private static final AtomicLong OFFSETS = new AtomicLong();

    @Autowired private WebApplicationContext context;
    @Autowired private DeadLetterMaterializationService materialization;
    @Autowired private DeadLetterEntryRepository entries;
    @Autowired private TenantRepository tenants;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;
    @Autowired private com.notify.auth.service.JwtService jwtService;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;
    private UUID tenantId;
    private String fixtureTopic;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        tenantId = tenants.save(new TenantEntity("ApiOrg", "api-" + UUID.randomUUID())).getId();
        fixtureTopic = "notify.test.dlq." + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM dlq_quarantine WHERE topic = ?", fixtureTopic);
        jdbc.update("DELETE FROM admin_audit_log WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    private String adminToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(
                                "admin@notify.local", "placeholder_admin_password_min_10_chars"))))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String tenantToken() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new SignupRequest(
                                "DlqApiTenant" + suffix, "dlq-api-" + suffix + "@notify.local", "Password123!"))))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private UUID seedEntry() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, 'WEBHOOK', 'FAILED', 'https://receiver.example.com/secret-hook', 'secret-subject',
                        'secret-body', 0, 1, 5, 5, now())
                """, id, tenantId);
        jdbc.update("""
                INSERT INTO delivery_attempts (id, tenant_id, notification_id, delivery_cycle, attempt_number,
                    provider, outcome, error_code, error_message, response_snippet, http_status,
                    started_at, finished_at, latency_ms)
                VALUES (?, ?, ?, 1, 5, 'webhook', 'RETRYABLE_FAILURE', 'WEBHOOK_SERVER_ERROR',
                        'receiver said secret-error-text', 'secret-response-snippet', 503, now(), now(), 12)
                """, UUID.randomUUID(), tenantId, id);
        DeadLetterEventEnvelope event = new DeadLetterEventEnvelope(
                1, UUID.randomUUID(), DeadLetterEventEnvelope.EVENT_TYPE, id, tenantId, Channel.WEBHOOK, 0, 1,
                DlqReason.MAX_RETRIES_EXCEEDED, "WEBHOOK_SERVER_ERROR", 5, clock.instant().toString());
        materialization.apply(event, new RecordCoordinates(
                fixtureTopic, 0, OFFSETS.incrementAndGet(), "{}".getBytes(StandardCharsets.UTF_8)));
        return id;
    }

    @Test
    @DisplayName("a tenant-owner JWT and an unauthenticated request are both rejected")
    void tenantCredentialsCannotReachTheDlqApi() throws Exception {
        String tenantJwt = tenantToken();

        mockMvc.perform(get("/api/v1/admin/dlq").header("Authorization", "Bearer " + tenantJwt))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/dlq/quarantine").header("Authorization", "Bearer " + tenantJwt))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/dlq"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/dlq").header("X-API-Key", "ntf_not_a_real_key"))
                .andExpect(status().isUnauthorized());
    }


    @Test
    @DisplayName("a token carrying the admin role on the tenant audience is rejected by every handler")
    void aCrossAudienceTokenIsRejected() throws Exception {
        // Deliberately mis-minted: the roles claim satisfies the chain, the audience must not satisfy the handler.
        // This is the case a role check alone lets through, and it reads across every tenant - so all nine
        // handlers are asserted, reads included.
        String crossAudience = jwtService
                .generateTenantToken(UUID.randomUUID(), tenantId, java.util.List.of("PLATFORM_ADMIN"))
                .token();
        UUID notificationId = seedEntry();
        UUID quarantineId = seedQuarantineRow();
        String bearer = "Bearer " + crossAudience;

        // 1-4: the reads.
        mockMvc.perform(get("/api/v1/admin/dlq").header("Authorization", bearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/dlq/" + notificationId).header("Authorization", bearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/dlq/" + notificationId + "/attempts").header("Authorization", bearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/dlq/" + notificationId + "/history").header("Authorization", bearer))
                .andExpect(status().isForbidden());
        // 5-6: the quarantine reads.
        mockMvc.perform(get("/api/v1/admin/dlq/quarantine").header("Authorization", bearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/dlq/quarantine/" + quarantineId).header("Authorization", bearer))
                .andExpect(status().isForbidden());
        // 7-9: the writes.
        mockMvc.perform(post("/api/v1/admin/dlq/" + notificationId + "/replay")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"should never run\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/dlq/" + notificationId + "/discard")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"should never run\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/dlq/quarantine/" + quarantineId + "/resolve")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"should never run\"}"))
                .andExpect(status().isForbidden());

        // Nothing the refused writes would have done happened.
        assertThat(entries.findByNotificationId(notificationId).orElseThrow().getStatus().name())
                .as("a refused replay or discard leaves the entry OPEN")
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject(
                "SELECT replay_count FROM dead_letter_entries WHERE notification_id = ?", Integer.class,
                notificationId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class,
                notificationId))
                .as("and the notification lifecycle is untouched")
                .isEqualTo("DLQ");
        assertThat(jdbc.queryForObject("SELECT status FROM dlq_quarantine WHERE id = ?", String.class,
                quarantineId))
                .as("a refused quarantine resolution leaves the record OPEN")
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE tenant_id = ?", Long.class, tenantId))
                .as("a refused action writes no audit row")
                .isZero();
    }

    /** One fixture-owned quarantine row, for the handlers that take a quarantine id. */
    private UUID seedQuarantineRow() {
        jdbc.update("""
                INSERT INTO dlq_quarantine (topic, partition_number, record_offset, dlt_reason, association,
                    claimed_notification_id, claimed_tenant_id)
                VALUES (?, 0, 91, 'POISON_PAYLOAD', 'UNKNOWN_NOTIFICATION', ?, ?)
                """, fixtureTopic, UUID.randomUUID(), UUID.randomUUID());
        return jdbc.queryForObject(
                "SELECT id FROM dlq_quarantine WHERE topic = ? AND record_offset = 91", UUID.class, fixtureTopic);
    }

    @Test
    @DisplayName("list filtering, keyset pagination and the limit ceiling behave as specified")
    void listFilteringAndPagination() throws Exception {
        String token = adminToken();
        seedEntry();
        seedEntry();
        seedEntry();

        MvcResult firstPage = mockMvc.perform(get("/api/v1/admin/dlq")
                        .param("status", "OPEN").param("channel", "WEBHOOK").param("tenantId", tenantId.toString())
                        .param("limit", "2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = mapper.readTree(firstPage.getResponse().getContentAsString());
        assertThat(page.get("items")).hasSize(2);
        String cursor = page.get("nextCursor").asText();
        assertThat(cursor).isNotBlank();

        MvcResult secondPage = mockMvc.perform(get("/api/v1/admin/dlq")
                        .param("tenantId", tenantId.toString()).param("limit", "2").param("cursor", cursor)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rest = mapper.readTree(secondPage.getResponse().getContentAsString());
        assertThat(rest.get("items")).hasSize(1);
        assertThat(rest.get("nextCursor").isNull()).as("the last page carries no cursor").isTrue();

        // Every rejection is 422 VALIDATION_FAILED, with no new error code.
        mockMvc.perform(get("/api/v1/admin/dlq").param("status", "NOT_A_STATUS")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/v1/admin/dlq").param("cursor", "not-a-cursor")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/v1/admin/dlq").param("limit", "5000")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/v1/admin/dlq")
                        .param("from", "2026-10-17T10:00:00Z").param("to", "2026-10-16T10:00:00Z")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("the detail projection exposes no recipient, body, URL, snippet or attempt message")
    void detailProjectionIsSafe() throws Exception {
        String token = adminToken();
        UUID notificationId = seedEntry();
        UUID entryId = entries.findByNotificationId(notificationId).orElseThrow().getId();

        MvcResult result = mockMvc.perform(get("/api/v1/admin/dlq/" + entryId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("secret-subject")
                .doesNotContain("secret-body")
                .doesNotContain("secret-hook")
                .doesNotContain("secret-error-text")
                .doesNotContain("secret-response-snippet");
        JsonNode detail = mapper.readTree(body);
        assertThat(detail.has("recipient")).as("no recipient field exists at all ()").isFalse();
        assertThat(detail.get("reason").asText()).isEqualTo("MAX_RETRIES_EXCEEDED");
        assertThat(detail.get("attempts")).hasSize(1);
        assertThat(detail.get("attempts").get(0).get("errorCode").asText()).isEqualTo("WEBHOOK_SERVER_ERROR");
        assertThat(detail.get("attempts").get(0).has("errorMessage")).isFalse();
        assertThat(detail.get("notification").get("currentStatus").asText()).isEqualTo("DLQ");
        assertThat(detail.get("payloadSnapshot").asText())
                .as("the snapshot is the dead-letter envelope, which carries no content")
                .contains("NOTIFICATION_DEAD_LETTERED");
    }

    @Test
    @DisplayName("a second replay through the API answers 409, and a discard requires its note")
    void replayAndDiscardThroughTheApi() throws Exception {
        String token = adminToken();
        UUID notificationId = seedEntry();
        UUID entryId = entries.findByNotificationId(notificationId).orElseThrow().getId();

        MvcResult replayed = mockMvc.perform(post("/api/v1/admin/dlq/" + entryId + "/replay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"receiver is healthy again\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = mapper.readTree(replayed.getResponse().getContentAsString());
        assertThat(response.get("newDeliveryCycle").asInt()).isEqualTo(2);
        assertThat(response.get("possibleDuplicateDelivery").asBoolean()).isTrue();
        assertThat(response.get("ambiguityNote").asText()).contains("new provider send identity");

        mockMvc.perform(post("/api/v1/admin/dlq/" + entryId + "/replay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());

        UUID other = seedEntry();
        UUID otherEntry = entries.findByNotificationId(other).orElseThrow().getId();
        mockMvc.perform(post("/api/v1/admin/dlq/" + otherEntry + "/discard")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/v1/admin/dlq/" + otherEntry + "/discard")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"receiver decommissioned\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/dlq/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the quarantine endpoints work with no dead_letter_entries row present")
    void quarantineEndpointsWorkWithoutAnEntry() throws Exception {
        String token = adminToken();
        jdbc.update("""
                INSERT INTO dlq_quarantine (topic, partition_number, record_offset, dlt_reason, association,
                    payload_sha256, payload_byte_count)
                VALUES (?, 0, 1, 'POISON_PAYLOAD', 'UNPARSABLE', repeat('a', 64), 11)
                """, fixtureTopic);
        jdbc.update("""
                INSERT INTO dlq_quarantine (topic, partition_number, record_offset, dlt_reason, association,
                    claimed_notification_id, claimed_tenant_id)
                VALUES (?, 0, 2, 'POISON_PAYLOAD', 'UNKNOWN_NOTIFICATION', ?, ?)
                """, fixtureTopic, UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("""
                INSERT INTO dlq_quarantine (topic, partition_number, record_offset, dlt_reason, association,
                    claimed_tenant_id, verified_tenant_id)
                VALUES (?, 0, 3, 'UNRESOLVED_PROCESSING_STUCK', 'TENANT_MISMATCH', ?, ?)
                """, fixtureTopic, UUID.randomUUID(), tenantId);

        MvcResult list = mockMvc.perform(get("/api/v1/admin/dlq/quarantine")
                        .param("topic", fixtureTopic)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = mapper.readTree(list.getResponse().getContentAsString()).get("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).has("claimedNotificationId"))
                .as("claimed values are labelled as claims in the DTO itself")
                .isTrue();
        assertThat(items.get(0).has("cycle")).as("no cycle is ever attributed to a record").isFalse();

        String quarantineId = items.get(0).get("quarantineId").asText();
        MvcResult detail = mockMvc.perform(get("/api/v1/admin/dlq/quarantine/" + quarantineId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode row = mapper.readTree(detail.getResponse().getContentAsString());
        assertThat(row.has("payload")).as("no raw payload is ever returned").isFalse();
        assertThat(row.has("payloadByteCount")).isTrue();

        mockMvc.perform(post("/api/v1/admin/dlq/quarantine/" + quarantineId + "/resolve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/v1/admin/dlq/quarantine/" + quarantineId + "/resolve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"reviewed: malformed producer, fixed upstream\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/dlq/quarantine/" + quarantineId + "/resolve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"again\"}"))
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log WHERE target_type = ?",
                Long.class, "DLQ_QUARANTINE"))
                .as("audited under the existing action value, with no migration")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a detail response embeds at most the configured attempts, with totals and a cursor")
    void detailListsAreBoundedAndCursored() throws Exception {
        String token = adminToken();
        UUID notificationId = seedEntry();
        UUID entryId = entries.findByNotificationId(notificationId).orElseThrow().getId();
        for (int attempt = 6; attempt <= 29; attempt++) {
            jdbc.update("""
                    INSERT INTO delivery_attempts (id, tenant_id, notification_id, delivery_cycle, attempt_number,
                        provider, outcome, error_code, started_at, finished_at, latency_ms)
                    VALUES (?, ?, ?, 1, ?, 'webhook', 'RETRYABLE_FAILURE', 'WEBHOOK_TIMEOUT', now(), now(), 5)
                    """, UUID.randomUUID(), tenantId, notificationId, attempt);
        }

        MvcResult result = mockMvc.perform(get("/api/v1/admin/dlq/" + entryId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detail = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(detail.get("attempts")).hasSize(20);
        assertThat(detail.get("attemptTotalCount").asInt()).isEqualTo(25);
        assertThat(detail.get("attemptsTruncated").asBoolean()).isTrue();

        MvcResult all = mockMvc.perform(get("/api/v1/admin/dlq/" + entryId + "/attempts")
                        .param("limit", "100")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(mapper.readTree(all.getResponse().getContentAsString()).get("items")).hasSize(25);
    }
}
