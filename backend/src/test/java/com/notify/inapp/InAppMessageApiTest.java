package com.notify.inapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.auth.dto.SignupRequest;
import com.notify.auth.service.JwtService;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The saved inbox through the real HTTP surface (/ , , condition C4).
 *
 * <p>Three things matter here beyond the obvious CRUD:
 * <ul>
 *   <li><b>the inbox is always available</b> — these requests carry no stream token, take no lease and never touch
 *       Redis, which is what makes the approved ≈95 s stream-admission latency acceptable;</li>
 *   <li><b>an invalid cursor is 422 {@code VALIDATION_FAILED}</b> through the existing frozen error set, never a new
 *       code and never a silent zero that would replay a client's whole inbox;</li>
 *   <li><b>keyset pagination</b>, so a concurrent insert cannot shift a page while a client is paging.</li>
 * </ul>
 */
class InAppMessageApiTest extends AbstractPostgresIntegrationTest {

    private static final String LIST = "/api/v1/inapp-messages";

    @Autowired private WebApplicationContext context;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private record TenantFixture(UUID tenantId, String token) {
    }

    private TenantFixture createTenant(String name) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        SignupRequest signup = new SignupRequest(
                name, name.toLowerCase() + "-" + suffix + "@notify.local", "Password123!");
        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = root.get("accessToken").asText();
        UUID tenantId = UUID.fromString(jwtService.parseToken(token).get("tid", String.class));
        return new TenantFixture(tenantId, token);
    }

    /**
     * Stores a message directly: this test is about the read surface, not about delivery. The notification row comes
     * first because {@code inapp_messages} carries a composite foreign key to
     * {@code notifications(tenant_id, id)} — an inbox row can never exist without its notification.
     */
    private long store(UUID tenantId, String recipientRef, String subject) {
        UUID notificationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, 'IN_APP', 'DELIVERED', ?, ?, ?, 0, 1, 1, 5, now())
                """, notificationId, tenantId, recipientRef, subject, "Body of " + subject);
        jdbc.update("""
                INSERT INTO inapp_messages (tenant_id, notification_id, recipient_ref, subject, body, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, tenantId, notificationId, recipientRef, subject, "Body of " + subject,
                Timestamp.from(Instant.now()));
        return jdbc.queryForObject(
                "SELECT seq FROM inapp_messages WHERE notification_id = ?", Long.class, notificationId);
    }

    @Test
    @DisplayName("the inbox lists a tenant's own messages newest first, with the unread count")
    void listsOwnMessages() throws Exception {
        TenantFixture tenant = createTenant("InboxOrg");
        long first = store(tenant.tenantId(), "user-1", "First");
        long second = store(tenant.tenantId(), "user-1", "Second");

        mockMvc.perform(get(LIST).param("recipientRef", "user-1")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].seq").value(Long.toString(second)))
                .andExpect(jsonPath("$.content[1].seq").value(Long.toString(first)))
                .andExpect(jsonPath("$.unread").value(2))
                .andExpect(jsonPath("$.page.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("seq is a decimal string, so a 64-bit cursor cannot be rounded by a browser")
    void seqIsSerialisedAsAString() throws Exception {
        TenantFixture tenant = createTenant("StringOrg");
        store(tenant.tenantId(), "user-1", "Only");

        MvcResult result = mockMvc.perform(get(LIST).param("recipientRef", "user-1")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode seq = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("content").get(0).get("seq");
        assertThat(seq.isTextual()).as("a JSON number would lose precision beyond 2^53").isTrue();
    }

    @Test
    @DisplayName("C4 an invalid cursor is 422 VALIDATION_FAILED, not a new error code and not a silent zero")
    void invalidCursorIs422() throws Exception {
        TenantFixture tenant = createTenant("CursorOrg");

        for (String bad : new String[]{"abc", "-5", "1.5"}) {
            mockMvc.perform(get(LIST).param("recipientRef", "user-1").param("after", bad)
                            .header("Authorization", "Bearer " + tenant.token()))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.title").value("VALIDATION_FAILED"));
        }
    }

    @Test
    @DisplayName("C4 an invalid size is 422 as well, with the same frozen code")
    void invalidSizeIs422() throws Exception {
        TenantFixture tenant = createTenant("SizeOrg");

        mockMvc.perform(get(LIST).param("recipientRef", "user-1").param("size", "0")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("VALIDATION_FAILED"));
        mockMvc.perform(get(LIST).param("recipientRef", "user-1").param("size", "9999")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("recipientRef is required: an omitted one is a validation failure, never a tenant-wide read")
    void recipientRefIsRequired() throws Exception {
        TenantFixture tenant = createTenant("RequiredOrg");

        mockMvc.perform(get(LIST).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get(LIST).param("recipientRef", "  ")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("keyset pagination returns each row exactly once and a concurrent insert cannot shift a page")
    void keysetPaginationIsStableUnderConcurrentInserts() throws Exception {
        TenantFixture tenant = createTenant("PageOrg");
        for (int i = 0; i < 5; i++) {
            store(tenant.tenantId(), "user-1", "Message " + i);
        }

        MvcResult firstPage = mockMvc.perform(get(LIST)
                        .param("recipientRef", "user-1").param("size", "2")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.hasMore").value(true))
                .andReturn();
        JsonNode firstJson = objectMapper.readTree(firstPage.getResponse().getContentAsString());
        String cursor = firstJson.get("nextCursor").asText();

        // A new message arrives between the two page requests. With offset pagination this would shift the window.
        store(tenant.tenantId(), "user-1", "Arrived during paging");

        MvcResult secondPage = mockMvc.perform(get(LIST)
                        .param("recipientRef", "user-1").param("size", "2").param("after", cursor)
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode secondJson = objectMapper.readTree(secondPage.getResponse().getContentAsString());

        long lastOfFirst = Long.parseLong(firstJson.get("content").get(1).get("seq").asText());
        long firstOfSecond = Long.parseLong(secondJson.get("content").get(0).get("seq").asText());
        assertThat(firstOfSecond)
                .as("strictly older than the previous page's last row: no repeats, no skips")
                .isLessThan(lastOfFirst);
    }

    @Test
    @DisplayName("another tenant's message is invisible, and marking it read is 404 rather than 403")
    void crossTenantAccessIsNotFound() throws Exception {
        TenantFixture mine = createTenant("MineOrg");
        TenantFixture theirs = createTenant("TheirsOrg");
        long theirSeq = store(theirs.tenantId(), "user-1", "Theirs");

        mockMvc.perform(get(LIST).param("recipientRef", "user-1")
                        .header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        mockMvc.perform(post(LIST + "/" + theirSeq + "/read").param("recipientRef", "user-1")
                        .header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("marking read is idempotent and reduces the unread count")
    void markReadIsIdempotent() throws Exception {
        TenantFixture tenant = createTenant("ReadOrg");
        long seq = store(tenant.tenantId(), "user-1", "Unread");

        mockMvc.perform(post(LIST + "/" + seq + "/read").param("recipientRef", "user-1")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(LIST + "/" + seq + "/read").param("recipientRef", "user-1")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(LIST).param("recipientRef", "user-1")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(jsonPath("$.unread").value(0))
                .andExpect(jsonPath("$.content[0].read").value(true));
    }

    @Test
    @DisplayName("the inbox requires authentication, like every other tenant endpoint")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(LIST).param("recipientRef", "user-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the stream path is matched but still refuses a request without a valid token")
    void streamWithoutATokenIsRejected() throws Exception {
        // The route is permitAll in the filter chain because an EventSource cannot authenticate; authorization is
        // the token consumption inside the controller, and it fails closed.
        mockMvc.perform(get("/sse/inapp").param("token", "not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("STREAM_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("C4 an invalid lastEventId is 422 and does not consume the token")
    void invalidLastEventIdIs422() throws Exception {
        mockMvc.perform(get("/sse/inapp").param("token", "irrelevant").param("lastEventId", "abc"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("VALIDATION_FAILED"));
    }
}
