package com.notify.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.auth.dto.SignupRequest;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.common.model.OutboxStatus;
import com.notify.notification.dto.CreateNotificationRequest;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.service.NotificationService;
import com.notify.outbox.entity.OutboxEventEntity;
import com.notify.outbox.repository.OutboxEventRepository;
import com.notify.quartz.service.NotificationReconcilerService;
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
import java.util.UUID;

import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "notify.reconciler.enabled=true",
        "notify.reconciler.grace-period-seconds=0"
})
// / -3: this class enables background work (relay, reconciler, reaper or worker). Spring caches
// test contexts for the whole JVM run, so without this the schedulers keep polling after the class finishes -
// and the outbox relay's fetch is global, with no tenant predicate. Dirtying the context shuts those pools
// down at class exit instead of leaking background polling into later suites.
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class TraceparentPreservationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationReconcilerService reconcilerService;

    @Autowired
    private com.notify.auth.service.JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private MockMvc mockMvc;

    private static final String VALID_TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String INVALID_TRACEPARENT = "00-00000000000000000000000000000000-0000000000000000-01";

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private record TenantFixture(UUID tenantId, String token) {}

    private TenantFixture createTenant(String name) throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        SignupRequest signup = new SignupRequest(name, name.toLowerCase() + "-" + uniqueSuffix + "@notify.local", "Password123!");
        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();
        var json = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = json.get("accessToken").asText();
        var claims = jwtService.parseToken(token);
        UUID tenantId = UUID.fromString(claims.get("tid", String.class));
        return new TenantFixture(tenantId, token);
    }

    @Test
    @DisplayName("Immediate acceptance preserves valid traceparent into notifications and outbox_events; null for absent/invalid")
    void immediateAcceptance_preservesTraceparent() throws Exception {
        TenantFixture tenant = createTenant("ImmediateTraceOrg");

        // 1. Valid traceparent
        CreateNotificationRequest reqValid = new CreateNotificationRequest(
                Channel.EMAIL, "user@test.com", "Subject", "Body", null, null, null, null, null, null
        );
        MvcResult resValid = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .header("traceparent", VALID_TRACEPARENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqValid)))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID idValid = UUID.fromString(objectMapper.readTree(resValid.getResponse().getContentAsString()).path("id").asText());

        NotificationEntity entityValid = notificationRepository.findByIdAndTenantId(idValid, tenant.tenantId()).orElseThrow();
        assertThat(entityValid.getTraceContext()).isEqualTo(VALID_TRACEPARENT);

        OutboxEventEntity outboxValid = outboxEventRepository.findByTenantIdAndStatus(tenant.tenantId(), OutboxStatus.PENDING)
                .stream().filter(e -> e.getAggregateId().equals(idValid)).findFirst().orElseThrow();
        assertThat(outboxValid.getTraceContext()).isEqualTo(VALID_TRACEPARENT);

        // 2. Absent traceparent
        CreateNotificationRequest reqAbsent = new CreateNotificationRequest(
                Channel.EMAIL, "user@test.com", "Subject", "Body", null, null, null, null, null, null
        );
        MvcResult resAbsent = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqAbsent)))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID idAbsent = UUID.fromString(objectMapper.readTree(resAbsent.getResponse().getContentAsString()).path("id").asText());

        NotificationEntity entityAbsent = notificationRepository.findByIdAndTenantId(idAbsent, tenant.tenantId()).orElseThrow();
        assertThat(entityAbsent.getTraceContext()).isNull();

        OutboxEventEntity outboxAbsent = outboxEventRepository.findByTenantIdAndStatus(tenant.tenantId(), OutboxStatus.PENDING)
                .stream().filter(e -> e.getAggregateId().equals(idAbsent)).findFirst().orElseThrow();
        assertThat(outboxAbsent.getTraceContext()).isNull();

        // 3. Invalid traceparent
        CreateNotificationRequest reqInvalid = new CreateNotificationRequest(
                Channel.EMAIL, "user@test.com", "Subject", "Body", null, null, null, null, null, null
        );
        MvcResult resInvalid = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .header("traceparent", INVALID_TRACEPARENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqInvalid)))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID idInvalid = UUID.fromString(objectMapper.readTree(resInvalid.getResponse().getContentAsString()).path("id").asText());

        NotificationEntity entityInvalid = notificationRepository.findByIdAndTenantId(idInvalid, tenant.tenantId()).orElseThrow();
        assertThat(entityInvalid.getTraceContext()).isNull();

        OutboxEventEntity outboxInvalid = outboxEventRepository.findByTenantIdAndStatus(tenant.tenantId(), OutboxStatus.PENDING)
                .stream().filter(e -> e.getAggregateId().equals(idInvalid)).findFirst().orElseThrow();
        assertThat(outboxInvalid.getTraceContext()).isNull();
    }

    @Test
    @DisplayName("Scheduled release preserves valid traceparent on background execution without HTTP context")
    void scheduledRelease_preservesTraceparent_onBackgroundExecution() throws Exception {
        TenantFixture tenant = createTenant("ScheduledTraceOrg");

        Instant scheduledAt = Instant.now().plus(1, ChronoUnit.HOURS);
        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.EMAIL, "user@test.com", "Subject", "Body", null, null, null, null, null, scheduledAt
        );

        MvcResult res = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .header("traceparent", VALID_TRACEPARENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn();

        UUID notifId = UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).path("id").asText());

        NotificationEntity entity = notificationRepository.findByIdAndTenantId(notifId, tenant.tenantId()).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(NotificationStatus.SCHEDULED);
        assertThat(entity.getTraceContext()).isEqualTo(VALID_TRACEPARENT);

        // Verify no outbox row was written prematurely
        assertThat(outboxEventRepository.findByTenantIdAndStatus(tenant.tenantId(), OutboxStatus.PENDING))
                .noneMatch(e -> e.getAggregateId().equals(notifId));

        // Simulate notification becoming due
        entity.setScheduledAt(Instant.now().minusSeconds(5));
        notificationRepository.save(entity);

        // Simulate Quartz execution on background thread (no RequestContextHolder)
        boolean released = notificationService.releaseScheduledNotification(tenant.tenantId(), notifId, 0);
        assertThat(released).isTrue();

        // Verify outbox row has the durably preserved traceparent
        OutboxEventEntity outbox = outboxEventRepository.findByTenantIdAndStatus(tenant.tenantId(), OutboxStatus.PENDING)
                .stream().filter(e -> e.getAggregateId().equals(notifId)).findFirst().orElseThrow();
        assertThat(outbox.getTraceContext()).isEqualTo(VALID_TRACEPARENT);
        assertThat(outbox.getEventType()).isEqualTo("NOTIFICATION_QUEUED");
    }

    @Test
    @DisplayName("Missing-trigger reconciler preserves traceparent when recovering overdue scheduled notification")
    void missingTriggerReconciler_preservesTraceparent() throws Exception {
        TenantFixture tenant = createTenant("ReconcilerTraceOrg");

        // Create overdue scheduled notification directly in DB with trace_context
        NotificationEntity overdue = new NotificationEntity();
        overdue.setTenantId(tenant.tenantId());
        overdue.setChannel(Channel.WEBHOOK);
        overdue.setStatus(NotificationStatus.SCHEDULED);
        overdue.setRecipient("https://example.com/webhook");
        overdue.setBody("payload");
        overdue.setScheduledAt(Instant.now().minus(2, ChronoUnit.MINUTES));
        overdue.setDispatchGeneration(0);
        overdue.setDeliveryCycle(1);
        overdue.setTraceContext(VALID_TRACEPARENT);
        overdue.setCreatedAt(Instant.now().minus(2, ChronoUnit.MINUTES));
        overdue.setUpdatedAt(Instant.now().minus(2, ChronoUnit.MINUTES));
        NotificationEntity savedOverdue = notificationRepository.save(overdue);
        UUID overdueId = savedOverdue.getId();

        // Run reconciler (simulates background cron job)
        int count = reconcilerService.reconcileOverdueScheduled();
        assertThat(count).isGreaterThanOrEqualTo(1);

        // Verify row transitioned to QUEUED
        NotificationEntity updated = notificationRepository.findByIdAndTenantId(overdueId, tenant.tenantId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(updated.getDispatchGeneration()).isEqualTo(1);

        // Verify outbox event carries preserved trace_context
        OutboxEventEntity outbox = outboxEventRepository.findByTenantIdAndStatus(tenant.tenantId(), OutboxStatus.PENDING)
                .stream().filter(e -> e.getAggregateId().equals(overdueId)).findFirst().orElseThrow();
        assertThat(outbox.getTraceContext()).isEqualTo(VALID_TRACEPARENT);
    }

    @Test
    @DisplayName("Retry release preserves traceparent on background execution and clears next_retry_at")
    void retryRelease_preservesTraceparent() throws Exception {
        TenantFixture tenant = createTenant("RetryTraceOrg");

        // Create a notification in RETRYING state with next_retry_at due now
        NotificationEntity retrying = new NotificationEntity();
        retrying.setTenantId(tenant.tenantId());
        retrying.setChannel(Channel.IN_APP);
        retrying.setStatus(NotificationStatus.RETRYING);
        retrying.setRecipient("user_123");
        retrying.setBody("In-app notification");
        retrying.setNextRetryAt(Instant.now().minus(10, ChronoUnit.SECONDS));
        retrying.setDispatchGeneration(1);
        retrying.setDeliveryCycle(1);
        retrying.setAttemptCount(1);
        retrying.setMaxAttempts(5);
        retrying.setTraceContext(VALID_TRACEPARENT);
        retrying.setCreatedAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        retrying.setUpdatedAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        NotificationEntity savedRetrying = notificationRepository.save(retrying);
        UUID retryingId = savedRetrying.getId();

        // Simulate background retry release
        boolean released = notificationService.releaseRetryNotification(tenant.tenantId(), retryingId, 1);
        assertThat(released).isTrue();

        NotificationEntity updated = notificationRepository.findByIdAndTenantId(retryingId, tenant.tenantId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(updated.getDispatchGeneration()).isEqualTo(2);
        assertThat(updated.getNextRetryAt()).isNull();

        // Verify outbox row carries preserved trace_context and NOTIFICATION_RETRY_DUE
        OutboxEventEntity outbox = outboxEventRepository.findByTenantIdAndStatus(tenant.tenantId(), OutboxStatus.PENDING)
                .stream().filter(e -> e.getAggregateId().equals(retryingId)).findFirst().orElseThrow();
        assertThat(outbox.getTraceContext()).isEqualTo(VALID_TRACEPARENT);
        assertThat(outbox.getEventType()).isEqualTo("NOTIFICATION_RETRY_DUE");
        assertThat(outbox.getTopic()).isEqualTo("notify.dispatch.inapp.v1");
    }
}
