package com.notify.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.auth.dto.SignupRequest;
import com.notify.common.model.Actor;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.common.model.TenantStatus;
import com.notify.notification.dto.CreateNotificationRequest;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.entity.NotificationStatusHistoryEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.repository.NotificationStatusHistoryRepository;
import com.notify.outbox.entity.OutboxEventEntity;
import com.notify.outbox.repository.OutboxEventRepository;
import com.notify.template.dto.CreateTemplateRequest;
import com.notify.template.dto.CreateVersionRequest;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationAcceptanceTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private com.notify.auth.service.JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private record TenantFixture(UUID tenantId, UUID userId, String token) {}

    private TenantFixture createTenant(String name) throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        SignupRequest signup = new SignupRequest(name, name.toLowerCase() + "-" + uniqueSuffix + "@notify.local", "Password123!");
        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = root.get("accessToken").asText();
        var claims = jwtService.parseToken(token);
        UUID tenantId = UUID.fromString(claims.get("tid", String.class));
        UUID userId = UUID.fromString(claims.getSubject());
        return new TenantFixture(tenantId, userId, token);
    }

    @Test
    @DisplayName("Immediate EMAIL notification creation persists row, status history, and outbox event")
    void createNotification_immediateEmail_persistsAtomically() throws Exception {
        TenantFixture tenant = createTenant("AcmeCorp");

        CreateNotificationRequest request = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "Order Confirmation #1024",
                "Your order #1024 has been processed successfully.",
                null,
                null,
                null,
                Map.of("orderId", 1024),
                null,
                null
        );

        MvcResult result = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID notificationId = UUID.fromString(json.path("id").asText());

        // 1. Verify notification persistence
        NotificationEntity notification = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(notification.getTenantId()).isEqualTo(tenant.tenantId());
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(notification.getChannel()).isEqualTo(Channel.EMAIL);
        assertThat(notification.getRecipient()).isEqualTo("user@example.com");
        assertThat(notification.getSubject()).isEqualTo("Order Confirmation #1024");
        assertThat(notification.getBody()).isEqualTo("Your order #1024 has been processed successfully.");
        assertThat(notification.getDispatchGeneration()).isEqualTo(1);
        assertThat(notification.getDeliveryCycle()).isEqualTo(1);

        // 2. Verify initial status history
        List<NotificationStatusHistoryEntity> history = statusHistoryRepository
                .findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenant.tenantId(), notificationId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getFromStatus()).isEqualTo(NotificationStatus.CREATED);
        assertThat(history.get(0).getToStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(history.get(0).getActor()).isEqualTo(Actor.SYSTEM);

        // 3. Verify transactional outbox event
        List<OutboxEventEntity> outboxEvents = outboxEventRepository.findByTenantIdAndStatus(tenant.tenantId(), com.notify.common.model.OutboxStatus.PENDING);
        assertThat(outboxEvents).anyMatch(e -> e.getAggregateId().equals(notificationId) && e.getEventType().equals("NOTIFICATION_QUEUED"));
        OutboxEventEntity queuedEvent = outboxEvents.stream()
                .filter(e -> e.getAggregateId().equals(notificationId))
                .findFirst().orElseThrow();
        JsonNode envelope = objectMapper.readTree(queuedEvent.getPayload());
        assertThat(envelope.path("generation").asInt()).isEqualTo(1);
        assertThat(queuedEvent.getTraceContext()).isNull();
    }

    @Test
    @DisplayName("Scheduled WEBHOOK notification persists as SCHEDULED without outbox event")
    void createNotification_scheduledWebhook_persistsWithoutOutbox() throws Exception {
        TenantFixture tenant = createTenant("ScheduledOrg");

        Instant futureSchedule = Instant.now().plus(2, ChronoUnit.HOURS);
        CreateNotificationRequest request = new CreateNotificationRequest(
                Channel.WEBHOOK,
                "https://api.partner.example.com/webhook",
                null,
                "{\"event\":\"partner.sync\"}",
                null,
                null,
                null,
                null,
                null,
                futureSchedule
        );

        MvcResult result = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID notificationId = UUID.fromString(json.path("id").asText());

        NotificationEntity notification = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SCHEDULED);
        assertThat(notification.getScheduledAt()).isNotNull();
        assertThat(notification.getDispatchGeneration()).isEqualTo(0);

        List<NotificationStatusHistoryEntity> history = statusHistoryRepository
                .findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenant.tenantId(), notificationId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getToStatus()).isEqualTo(NotificationStatus.SCHEDULED);

        // Outbox event should NOT be created for scheduled notifications until released by Quartz in 
        List<OutboxEventEntity> outboxEvents = outboxEventRepository.findByTenantIdAndStatus(tenant.tenantId(), com.notify.common.model.OutboxStatus.PENDING);
        assertThat(outboxEvents).noneMatch(e -> e.getAggregateId().equals(notificationId));
    }

    @Test
    @DisplayName("Notification referencing template renders variables into body snapshot")
    void createNotification_withTemplate_rendersVariables() throws Exception {
        TenantFixture tenant = createTenant("TemplateSender");

        // 1. Create and publish template
        CreateTemplateRequest templateReq = new CreateTemplateRequest(
                "Welcome Template",
                Channel.EMAIL,
                "Welcome email",
                "Welcome {{name}}!",
                "Hello {{name}}, welcome to Notify!",
                com.notify.common.model.ContentType.TEXT
        );
        MvcResult tmplRes = mockMvc.perform(post("/api/v1/templates")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(templateReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID templateId = UUID.fromString(objectMapper.readTree(tmplRes.getResponse().getContentAsString()).path("id").asText());

        // Publish version 1
        mockMvc.perform(post("/api/v1/templates/" + templateId + "/versions/1/publish")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk());

        // 2. Create notification using published template
        CreateNotificationRequest notifReq = new CreateNotificationRequest(
                Channel.EMAIL,
                "alice@example.com",
                null,
                null,
                templateId,
                null,
                Map.of("name", "Alice"),
                null,
                null,
                null
        );

        MvcResult notifRes = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(notifReq)))
                .andExpect(status().isAccepted())
                .andReturn();

        UUID notificationId = UUID.fromString(objectMapper.readTree(notifRes.getResponse().getContentAsString()).path("id").asText());
        NotificationEntity notification = notificationRepository.findById(notificationId).orElseThrow();

        assertThat(notification.getSubject()).isEqualTo("Welcome Alice!");
        assertThat(notification.getBody()).isEqualTo("Hello Alice, welcome to Notify!");
        assertThat(notification.getTemplateVersionId()).isNotNull();
    }

    @Test
    @DisplayName("Missing template variables returns HTTP 422 TEMPLATE_VARIABLE_MISSING")
    void createNotification_missingTemplateVariable_returns422() throws Exception {
        TenantFixture tenant = createTenant("MissingVarOrg");

        CreateTemplateRequest templateReq = new CreateTemplateRequest(
                "Order Template",
                Channel.EMAIL,
                null,
                "Order {{orderNumber}}",
                "Total: {{amount}}",
                com.notify.common.model.ContentType.TEXT
        );
        MvcResult tmplRes = mockMvc.perform(post("/api/v1/templates")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(templateReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID templateId = UUID.fromString(objectMapper.readTree(tmplRes.getResponse().getContentAsString()).path("id").asText());

        mockMvc.perform(post("/api/v1/templates/" + templateId + "/versions/1/publish")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk());

        // Send with missing amount variable
        CreateNotificationRequest notifReq = new CreateNotificationRequest(
                Channel.EMAIL,
                "bob@example.com",
                null,
                null,
                templateId,
                null,
                Map.of("orderNumber", "99"),
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(notifReq)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("TEMPLATE_VARIABLE_MISSING"));
    }

    @Test
    @DisplayName("Validation failures return 422 VALIDATION_FAILED and SCHEDULE_OUT_OF_RANGE")
    void createNotification_validations() throws Exception {
        TenantFixture tenant = createTenant("ValidationOrg");

        // Invalid email format
        CreateNotificationRequest invalidEmail = new CreateNotificationRequest(
                Channel.EMAIL,
                "not-an-email",
                "Sub",
                "Body",
                null,
                null,
                null,
                null,
                null,
                null
        );
        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidEmail)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        // SEC-01 Regression: Malformed domain with consecutive dots or missing TLD returns 422 VALIDATION_FAILED
        for (String malformedEmail : List.of("user@domain..com", "user@.domain.com", "user@domain", "user@-domain.com")) {
            CreateNotificationRequest req = new CreateNotificationRequest(
                    Channel.EMAIL,
                    malformedEmail,
                    "Sub",
                    "Body",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
            mockMvc.perform(post("/api/v1/notifications")
                            .header("Authorization", "Bearer " + tenant.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }

        // Schedule > 90 days
        Instant beyond90Days = Instant.now().plus(95, ChronoUnit.DAYS);
        CreateNotificationRequest outOfRangeSchedule = new CreateNotificationRequest(
                Channel.IN_APP,
                "user-1",
                null,
                "Body",
                null,
                null,
                null,
                null,
                null,
                beyond90Days
        );
        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(outOfRangeSchedule)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SCHEDULE_OUT_OF_RANGE"));
    }

    @Test
    @DisplayName("Tenant isolation: querying cross-tenant notification returns 404 NOT_FOUND")
    void crossTenantIsolation_returns404() throws Exception {
        TenantFixture tenant1 = createTenant("TenantOne");
        TenantFixture tenant2 = createTenant("TenantTwo");

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.IN_APP,
                "recipient-1",
                null,
                "Hello Tenant 1",
                null,
                null,
                null,
                null,
                null,
                null
        );
        MvcResult res = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID notificationId = UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).path("id").asText());

        // Tenant 2 reading Tenant 1's notification must receive 404
        mockMvc.perform(get("/api/v1/notifications/" + notificationId)
                        .header("Authorization", "Bearer " + tenant2.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));

        // Tenant 2 reading history of Tenant 1's notification must receive 404
        mockMvc.perform(get("/api/v1/notifications/" + notificationId + "/history")
                        .header("Authorization", "Bearer " + tenant2.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Suspended tenant is rejected with 403 TENANT_SUSPENDED")
    void suspendedTenant_rejectedWith403() throws Exception {
        TenantFixture tenant = createTenant("SuspendedTenant");

        // Suspend tenant in DB
        tenantRepository.findById(tenant.tenantId()).ifPresent(t -> {
            t.setStatus(TenantStatus.SUSPENDED);
            tenantRepository.save(t);
        });

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.IN_APP,
                "recipient-1",
                null,
                "Hello",
                null,
                null,
                null,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_SUSPENDED"));
    }

    @Test
    @DisplayName("Outbox trace_context captures valid W3C traceparent header or null when invalid/missing")
    void outboxTraceContext_validAndInvalidHeaders() throws Exception {
        TenantFixture tenant = createTenant("TraceOrg");

        String validTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        CreateNotificationRequest reqValid = new CreateNotificationRequest(
                Channel.EMAIL,
                "trace-valid@example.com",
                "Trace Valid",
                "Testing valid traceparent",
                null, null, null, null, null, null
        );

        MvcResult resValid = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .header("traceparent", validTraceparent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqValid)))
                .andExpect(status().isAccepted())
                .andReturn();

        UUID notifIdValid = UUID.fromString(objectMapper.readTree(resValid.getResponse().getContentAsString()).path("id").asText());
        OutboxEventEntity outboxValid = outboxEventRepository.findByTenantIdAndStatus(tenant.tenantId(), com.notify.common.model.OutboxStatus.PENDING)
                .stream()
                .filter(e -> e.getAggregateId().equals(notifIdValid))
                .findFirst().orElseThrow();
        assertThat(outboxValid.getTraceContext()).isEqualTo(validTraceparent);

        // Invalid traceparent
        CreateNotificationRequest reqInvalid = new CreateNotificationRequest(
                Channel.EMAIL,
                "trace-invalid@example.com",
                "Trace Invalid",
                "Testing invalid traceparent",
                null, null, null, null, null, null
        );

        MvcResult resInvalid = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .header("traceparent", "malformed-traceparent-header")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqInvalid)))
                .andExpect(status().isAccepted())
                .andReturn();

        UUID notifIdInvalid = UUID.fromString(objectMapper.readTree(resInvalid.getResponse().getContentAsString()).path("id").asText());
        OutboxEventEntity outboxInvalid = outboxEventRepository.findByTenantIdAndStatus(tenant.tenantId(), com.notify.common.model.OutboxStatus.PENDING)
                .stream()
                .filter(e -> e.getAggregateId().equals(notifIdInvalid))
                .findFirst().orElseThrow();
        assertThat(outboxInvalid.getTraceContext()).isNull();
    }

    @Test
    @DisplayName("Explicit DRAFT template version returns HTTP 422 VALIDATION_FAILED")
    void createNotification_explicitDraftTemplateVersion_returns422() throws Exception {
        TenantFixture tenant = createTenant("DraftTmplOrg");

        // 1. Create template (version 1 is DRAFT by default)
        CreateTemplateRequest templateReq = new CreateTemplateRequest(
                "Draft Template",
                Channel.EMAIL,
                "Draft desc",
                "Draft {{title}}",
                "Hello {{body}}",
                com.notify.common.model.ContentType.TEXT
        );
        MvcResult tmplRes = mockMvc.perform(post("/api/v1/templates")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(templateReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID templateId = UUID.fromString(objectMapper.readTree(tmplRes.getResponse().getContentAsString()).path("id").asText());

        // 2. Try to create notification with explicit version 1 (which is DRAFT)
        CreateNotificationRequest notifReq = new CreateNotificationRequest(
                Channel.EMAIL,
                "draft@example.com",
                null,
                null,
                templateId,
                1,
                Map.of("title", "Test", "body", "Test body"),
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(notifReq)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("Explicit PUBLISHED and ARCHIVED template versions succeed; default resolves PUBLISHED")
    void createNotification_explicitPublishedAndArchivedVersions_succeed() throws Exception {
        TenantFixture tenant = createTenant("VersionStatusOrg");

        // 1. Create template (version 1 DRAFT)
        CreateTemplateRequest templateReq = new CreateTemplateRequest(
                "MultiVersion Template",
                Channel.EMAIL,
                "Desc",
                "Subject v1 {{v}}",
                "Body v1 {{v}}",
                com.notify.common.model.ContentType.TEXT
        );
        MvcResult tmplRes = mockMvc.perform(post("/api/v1/templates")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(templateReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID templateId = UUID.fromString(objectMapper.readTree(tmplRes.getResponse().getContentAsString()).path("id").asText());

        // Publish version 1
        mockMvc.perform(post("/api/v1/templates/" + templateId + "/versions/1/publish")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk());

        // Create version 2
        CreateVersionRequest v2Req = new CreateVersionRequest(
                "Subject v2 {{v}}",
                "Body v2 {{v}}",
                com.notify.common.model.ContentType.TEXT
        );
        mockMvc.perform(post("/api/v1/templates/" + templateId + "/versions")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(v2Req)))
                .andExpect(status().isCreated());

        // Publish version 2 -> version 1 becomes ARCHIVED, version 2 becomes PUBLISHED
        mockMvc.perform(post("/api/v1/templates/" + templateId + "/versions/2/publish")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk());

        // A. Pinned ARCHIVED version 1 succeeds
        CreateNotificationRequest archivedReq = new CreateNotificationRequest(
                Channel.EMAIL,
                "archived@example.com",
                null,
                null,
                templateId,
                1,
                Map.of("v", "one"),
                null,
                null,
                null
        );
        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(archivedReq)))
                .andExpect(status().isAccepted());

        // B. Pinned PUBLISHED version 2 succeeds
        CreateNotificationRequest publishedReq = new CreateNotificationRequest(
                Channel.EMAIL,
                "published@example.com",
                null,
                null,
                templateId,
                2,
                Map.of("v", "two"),
                null,
                null,
                null
        );
        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(publishedReq)))
                .andExpect(status().isAccepted());

        // C. Unpinned resolves PUBLISHED (version 2)
        CreateNotificationRequest defaultReq = new CreateNotificationRequest(
                Channel.EMAIL,
                "default@example.com",
                null,
                null,
                templateId,
                null,
                Map.of("v", "two-default"),
                null,
                null,
                null
        );
        MvcResult resDefault = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(defaultReq)))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID notifId = UUID.fromString(objectMapper.readTree(resDefault.getResponse().getContentAsString()).path("id").asText());
        NotificationEntity entity = notificationRepository.findById(notifId).orElseThrow();
        assertThat(entity.getSubject()).isEqualTo("Subject v2 two-default");
    }
}

