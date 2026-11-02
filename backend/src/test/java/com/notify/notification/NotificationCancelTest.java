package com.notify.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.auth.dto.SignupRequest;
import com.notify.common.model.Actor;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.notification.dto.CreateNotificationRequest;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.entity.NotificationStatusHistoryEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.repository.NotificationStatusHistoryRepository;
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
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationCancelTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private com.notify.notification.model.NotificationTransitioner notificationTransitioner;

    @Autowired
    private com.notify.notification.service.NotificationService notificationService;

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
    @DisplayName("Tenant can cancel QUEUED notification with checked conditional update and audit history")
    void cancelNotification_queued_succeeds() throws Exception {
        TenantFixture tenant = createTenant("CancelOrg");

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.IN_APP,
                "user-100",
                null,
                "Notification to cancel",
                null,
                null,
                null,
                null,
                null,
                null
        );
        MvcResult createRes = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn();

        UUID notificationId = UUID.fromString(objectMapper.readTree(createRes.getResponse().getContentAsString()).path("id").asText());

        // Cancel notification
        mockMvc.perform(post("/api/v1/notifications/" + notificationId + "/cancel")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "User requested cancel"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(notificationId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Verify status in DB
        NotificationEntity notification = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.CANCELLED);
        assertThat(notification.getCancelReason()).isEqualTo("User requested cancel");
        assertThat(notification.getCompletedAt()).isNotNull();

        // Verify history has CANCELLED entry with actor = TENANT
        List<NotificationStatusHistoryEntity> history = statusHistoryRepository
                .findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenant.tenantId(), notificationId);
        assertThat(history).hasSize(2);
        assertThat(history.get(1).getToStatus()).isEqualTo(NotificationStatus.CANCELLED);
        assertThat(history.get(1).getActor()).isEqualTo(Actor.TENANT);
        assertThat(history.get(1).getReason()).isEqualTo("User requested cancel");
    }

    @Test
    @DisplayName("Cancelling notification in non-cancellable state returns 409 INVALID_STATE_TRANSITION")
    void cancelNotification_alreadyDelivered_returns409() throws Exception {
        TenantFixture tenant = createTenant("NonCancellableOrg");

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.IN_APP,
                "user-100",
                null,
                "Notification already delivered",
                null,
                null,
                null,
                null,
                null,
                null
        );
        MvcResult createRes = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn();

        UUID notificationId = UUID.fromString(objectMapper.readTree(createRes.getResponse().getContentAsString()).path("id").asText());

        // Simulate worker delivering the notification
        NotificationEntity n = notificationRepository.findById(notificationId).orElseThrow();
        n.setStatus(NotificationStatus.DELIVERED);
        notificationRepository.save(n);

        // Attempt to cancel DELIVERED notification
        mockMvc.perform(post("/api/v1/notifications/" + notificationId + "/cancel")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "Too late"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("Cancelling another tenant's notification returns 404 NOT_FOUND")
    void cancelNotification_crossTenant_returns404() throws Exception {
        TenantFixture tenant1 = createTenant("TenantA");
        TenantFixture tenant2 = createTenant("TenantB");

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.IN_APP,
                "user-1",
                null,
                "Tenant A message",
                null,
                null,
                null,
                null,
                null,
                null
        );
        MvcResult createRes = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn();

        UUID notificationId = UUID.fromString(objectMapper.readTree(createRes.getResponse().getContentAsString()).path("id").asText());

        // Tenant B attempts to cancel Tenant A's notification
        mockMvc.perform(post("/api/v1/notifications/" + notificationId + "/cancel")
                        .header("Authorization", "Bearer " + tenant2.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("Cancel and schedule-release: accurate history and conditional behavior for both legal sequential orders")
    void cancel_and_scheduleRelease_sequentialTransitions_bothValidOrders() throws Exception {
        TenantFixture tenant = createTenant("SequentialOrdersOrg");

        // Order 1: SCHEDULED -> QUEUED (release) then QUEUED -> CANCELLED (cancel)
        CreateNotificationRequest req1 = new CreateNotificationRequest(
                Channel.EMAIL, "seq1@example.com", "Seq 1 Subject", "Seq 1 Body",
                null, null, null, null, null, Instant.now().plus(2, ChronoUnit.HOURS)
        );
        MvcResult res1 = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID id1 = UUID.fromString(objectMapper.readTree(res1.getResponse().getContentAsString()).path("id").asText());

        // Step 1A: Release SCHEDULED -> QUEUED (generation 0 -> 1)
        notificationTransitioner.transition(
                tenant.tenantId(), id1, NotificationStatus.SCHEDULED, NotificationStatus.QUEUED,
                Actor.SYSTEM, 0, "Schedule released"
        );
        NotificationEntity e1AfterRelease = notificationRepository.findById(id1).orElseThrow();
        assertThat(e1AfterRelease.getStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(e1AfterRelease.getDispatchGeneration()).isEqualTo(0);

        // Step 1B: Later cancellation of QUEUED notification
        notificationService.cancelNotification(tenant.tenantId(), id1, "Tenant cancelled after release");
        NotificationEntity e1AfterCancel = notificationRepository.findById(id1).orElseThrow();
        assertThat(e1AfterCancel.getStatus()).isEqualTo(NotificationStatus.CANCELLED);
        assertThat(e1AfterCancel.getCancelReason()).isEqualTo("Tenant cancelled after release");
        assertThat(e1AfterCancel.getCompletedAt()).isNotNull();

        // Accurate 3-step history for Order 1
        List<NotificationStatusHistoryEntity> h1 = statusHistoryRepository
                .findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenant.tenantId(), id1);
        assertThat(h1).hasSize(3);
        assertThat(h1.get(0).getToStatus()).isEqualTo(NotificationStatus.SCHEDULED);
        assertThat(h1.get(1).getFromStatus()).isEqualTo(NotificationStatus.SCHEDULED);
        assertThat(h1.get(1).getToStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(h1.get(1).getActor()).isEqualTo(Actor.SYSTEM);
        assertThat(h1.get(2).getFromStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(h1.get(2).getToStatus()).isEqualTo(NotificationStatus.CANCELLED);
        assertThat(h1.get(2).getActor()).isEqualTo(Actor.TENANT);

        // Order 2: SCHEDULED -> CANCELLED (cancel) then attempted release SCHEDULED -> QUEUED
        CreateNotificationRequest req2 = new CreateNotificationRequest(
                Channel.EMAIL, "seq2@example.com", "Seq 2 Subject", "Seq 2 Body",
                null, null, null, null, null, Instant.now().plus(2, ChronoUnit.HOURS)
        );
        MvcResult res2 = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID id2 = UUID.fromString(objectMapper.readTree(res2.getResponse().getContentAsString()).path("id").asText());

        // Step 2A: Cancel while SCHEDULED
        notificationService.cancelNotification(tenant.tenantId(), id2, "Tenant cancelled before release");
        NotificationEntity e2AfterCancel = notificationRepository.findById(id2).orElseThrow();
        assertThat(e2AfterCancel.getStatus()).isEqualTo(NotificationStatus.CANCELLED);

        // Step 2B: Attempted release fails with 409 INVALID_STATE_TRANSITION because row is CANCELLED
        com.notify.common.error.NotifyException releaseEx = org.junit.jupiter.api.Assertions.assertThrows(
                com.notify.common.error.NotifyException.class,
                () -> notificationTransitioner.transition(
                        tenant.tenantId(), id2, NotificationStatus.SCHEDULED, NotificationStatus.QUEUED,
                        Actor.SYSTEM, 0, "Schedule released"
                )
        );
        assertThat(releaseEx.getErrorCode()).isEqualTo(com.notify.common.error.ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(releaseEx.getStatus().value()).isEqualTo(409);

        // Accurate 2-step history for Order 2 (failed release left zero history rows)
        List<NotificationStatusHistoryEntity> h2 = statusHistoryRepository
                .findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenant.tenantId(), id2);
        assertThat(h2).hasSize(2);
        assertThat(h2.get(0).getToStatus()).isEqualTo(NotificationStatus.SCHEDULED);
        assertThat(h2.get(1).getFromStatus()).isEqualTo(NotificationStatus.SCHEDULED);
        assertThat(h2.get(1).getToStatus()).isEqualTo(NotificationStatus.CANCELLED);
        assertThat(h2.get(1).getActor()).isEqualTo(Actor.TENANT);
    }

    @Test
    @DisplayName("Cancel versus schedule-release contention: forcing same observed state yields one winner and one 409 loser")
    void cancel_versus_scheduleRelease_contention_forcesSameObservedState() throws Exception {
        TenantFixture tenant = createTenant("CancelVsReleaseContentionOrg");

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.EMAIL, "sched@example.com", "Scheduled Subject", "Scheduled Body",
                null, null, null, null, null, Instant.now().plus(2, ChronoUnit.HOURS)
        );
        MvcResult createRes = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn();

        UUID notificationId = UUID.fromString(objectMapper.readTree(createRes.getResponse().getContentAsString()).path("id").asText());

        // Concurrently attempt cancel and release competing on the exact same observed state (SCHEDULED, gen 0)
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<String> cancelTask = executor.submit(() -> {
            barrier.await();
            try {
                notificationTransitioner.cancel(
                        tenant.tenantId(), notificationId, NotificationStatus.SCHEDULED, Actor.TENANT, 0,
                        "Tenant cancelled concurrently"
                );
                return "CANCEL_WON";
            } catch (com.notify.common.error.NotifyException ex) {
                assertThat(ex.getErrorCode()).isEqualTo(com.notify.common.error.ErrorCode.INVALID_STATE_TRANSITION);
                assertThat(ex.getStatus().value()).isEqualTo(409);
                return "CANCEL_LOST";
            }
        });

        Future<String> releaseTask = executor.submit(() -> {
            barrier.await();
            try {
                notificationTransitioner.transition(
                        tenant.tenantId(), notificationId, NotificationStatus.SCHEDULED, NotificationStatus.QUEUED,
                        Actor.SYSTEM, 0, "Schedule released concurrently"
                );
                return "RELEASE_WON";
            } catch (com.notify.common.error.NotifyException ex) {
                assertThat(ex.getErrorCode()).isEqualTo(com.notify.common.error.ErrorCode.INVALID_STATE_TRANSITION);
                assertThat(ex.getStatus().value()).isEqualTo(409);
                return "RELEASE_LOST";
            }
        });

        String cancelResult = cancelTask.get(5, TimeUnit.SECONDS);
        String releaseResult = releaseTask.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        boolean releaseWon = "RELEASE_WON".equals(releaseResult);

        // Contention resolution with race recovery:
        // If cancel runs first: cancel wins from SCHEDULED, release gets 409
        // If release runs first: release transitions to QUEUED, cancel recovers against QUEUED and succeeds
        // In both cases, cancellation succeeds, final entity status is CANCELLED, and exactly 1 cancel history row exists
        assertThat(cancelResult).isEqualTo("CANCEL_WON");

        NotificationEntity entity = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(NotificationStatus.CANCELLED);
        assertThat(entity.getCancelReason()).isEqualTo("Tenant cancelled concurrently");

        List<NotificationStatusHistoryEntity> history = statusHistoryRepository
                .findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenant.tenantId(), notificationId);
        long cancelCount = history.stream().filter(h -> h.getToStatus() == NotificationStatus.CANCELLED).count();
        assertThat(cancelCount).isEqualTo(1);

        if (releaseWon) {
            assertThat(history).hasSize(3);
            assertThat(history.get(0).getToStatus()).isEqualTo(NotificationStatus.SCHEDULED);
            assertThat(history.get(1).getToStatus()).isEqualTo(NotificationStatus.QUEUED);
            assertThat(history.get(2).getToStatus()).isEqualTo(NotificationStatus.CANCELLED);
        } else {
            assertThat(history).hasSize(2);
            assertThat(history.get(0).getToStatus()).isEqualTo(NotificationStatus.SCHEDULED);
            assertThat(history.get(1).getToStatus()).isEqualTo(NotificationStatus.CANCELLED);
        }
    }

    @Test
    @DisplayName("Cancel versus worker-claim: exactly one valid winner, accurate history, loser gets 409")
    void cancel_versus_workerClaim_concurrent() throws Exception {
        TenantFixture tenant = createTenant("CancelVsClaimOrg");

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.EMAIL,
                "claim@example.com",
                "Claim Subject",
                "Claim Body",
                null,
                null,
                null,
                null,
                null,
                null
        );
        MvcResult createRes = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn();

        UUID notificationId = UUID.fromString(objectMapper.readTree(createRes.getResponse().getContentAsString()).path("id").asText());

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<String> cancelTask = executor.submit(() -> {
            barrier.await();
            try {
                notificationService.cancelNotification(tenant.tenantId(), notificationId, "Tenant cancelled");
                return "CANCEL_WON";
            } catch (com.notify.common.error.NotifyException ex) {
                assertThat(ex.getErrorCode()).isEqualTo(com.notify.common.error.ErrorCode.INVALID_STATE_TRANSITION);
                assertThat(ex.getStatus().value()).isEqualTo(409);
                return "CANCEL_LOST";
            }
        });

        Future<String> claimTask = executor.submit(() -> {
            barrier.await();
            try {
                notificationTransitioner.transition(
                        tenant.tenantId(), notificationId, NotificationStatus.QUEUED, NotificationStatus.PROCESSING,
                        Actor.SYSTEM, 1, "Worker claim"
                );
                return "CLAIM_WON";
            } catch (com.notify.common.error.NotifyException ex) {
                assertThat(ex.getErrorCode()).isEqualTo(com.notify.common.error.ErrorCode.INVALID_STATE_TRANSITION);
                assertThat(ex.getStatus().value()).isEqualTo(409);
                return "CLAIM_LOST";
            }
        });

        String cancelResult = cancelTask.get(5, TimeUnit.SECONDS);
        String claimResult = claimTask.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        if ("CANCEL_WON".equals(cancelResult)) {
            assertThat(claimResult).isEqualTo("CLAIM_LOST");
            NotificationEntity entity = notificationRepository.findById(notificationId).orElseThrow();
            assertThat(entity.getStatus()).isEqualTo(NotificationStatus.CANCELLED);
            assertThat(entity.getCancelReason()).isEqualTo("Tenant cancelled");
            assertThat(entity.getCompletedAt()).isNotNull();

            List<NotificationStatusHistoryEntity> history = statusHistoryRepository
                    .findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenant.tenantId(), notificationId);
            assertThat(history).hasSize(2);
            assertThat(history.get(0).getToStatus()).isEqualTo(NotificationStatus.QUEUED);
            assertThat(history.get(1).getFromStatus()).isEqualTo(NotificationStatus.QUEUED);
            assertThat(history.get(1).getToStatus()).isEqualTo(NotificationStatus.CANCELLED);
            assertThat(history.get(1).getActor()).isEqualTo(Actor.TENANT);
        } else {
            assertThat(cancelResult).isEqualTo("CANCEL_LOST");
            assertThat(claimResult).isEqualTo("CLAIM_WON");
            NotificationEntity entity = notificationRepository.findById(notificationId).orElseThrow();
            assertThat(entity.getStatus()).isEqualTo(NotificationStatus.PROCESSING);

            List<NotificationStatusHistoryEntity> history = statusHistoryRepository
                    .findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenant.tenantId(), notificationId);
            assertThat(history).hasSize(2);
            assertThat(history.get(0).getToStatus()).isEqualTo(NotificationStatus.QUEUED);
            assertThat(history.get(1).getFromStatus()).isEqualTo(NotificationStatus.QUEUED);
            assertThat(history.get(1).getToStatus()).isEqualTo(NotificationStatus.PROCESSING);
            assertThat(history.get(1).getActor()).isEqualTo(Actor.SYSTEM);
        }
    }

    @Test
    @DisplayName("Cancel reason exceeding 64 chars returns 422 VALIDATION_FAILED with no partial writes")
    void cancelNotification_oversizedReason_returns422ValidationFailed() throws Exception {
        TenantFixture tenant = createTenant("OversizedOrg");

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.IN_APP,
                "user-oversize",
                null,
                "Test message",
                null, null, null, null, null, null
        );
        MvcResult createRes = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn();

        UUID notificationId = UUID.fromString(objectMapper.readTree(createRes.getResponse().getContentAsString()).path("id").asText());

        String longReason = "A".repeat(65);
        mockMvc.perform(post("/api/v1/notifications/" + notificationId + "/cancel")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", longReason))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        // Verify no partial writes
        NotificationEntity entity = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(entity.getCancelReason()).isNull();
        assertThat(entity.getCompletedAt()).isNull();

        List<NotificationStatusHistoryEntity> history = statusHistoryRepository
                .findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenant.tenantId(), notificationId);
        assertThat(history).hasSize(1);
    }

    @Test
    @DisplayName("Scheduled-release vs tenant-cancel race: release transitions SCHEDULED->QUEUED, cancel recovers against QUEUED with exactly 1 cancel history row")
    void cancelRace_scheduledReleaseWins_recoversAgainstQueued_singleCancelHistory() throws Exception {
        TenantFixture tenant = createTenant("RaceOrg");

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.IN_APP,
                "user-race",
                null,
                "Race notification",
                null, null, null, null, null,
                Instant.now().plus(1, ChronoUnit.HOURS)
        );
        MvcResult createRes = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isAccepted())
            .andReturn();

        UUID notificationId = UUID.fromString(objectMapper.readTree(createRes.getResponse().getContentAsString()).path("id").asText());

        // 1. Advance scheduled_at and trigger release execution: SCHEDULED -> QUEUED (generation 0 -> 1)
        NotificationEntity entity = notificationRepository.findById(notificationId).orElseThrow();
        entity.setScheduledAt(Instant.now().minus(10, ChronoUnit.SECONDS));
        notificationRepository.save(entity);

        boolean released = notificationService.releaseScheduledNotification(tenant.tenantId(), notificationId, 0);
        assertThat(released).isTrue();

        // Verify it is now in QUEUED status with generation 1
        NotificationEntity afterRelease = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(afterRelease.getStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(afterRelease.getDispatchGeneration()).isEqualTo(1);

        // 2. Tenant cancel arrives with stale fromStatus=SCHEDULED and expectedGen=0 (having read before release committed)
        // NotificationTransitioner.cancel must detect the transition to QUEUED, recover, and cancel successfully
        notificationTransitioner.cancel(
                tenant.tenantId(),
                notificationId,
                NotificationStatus.SCHEDULED,
                Actor.TENANT,
                0,
                "Cancelled after race"
        );

        NotificationEntity afterCancel = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(afterCancel.getStatus()).isEqualTo(NotificationStatus.CANCELLED);
        assertThat(afterCancel.getCancelReason()).isEqualTo("Cancelled after race");

        // Exactly 3 history rows total:
        // 1: CREATED -> SCHEDULED (initial creation)
        // 2: SCHEDULED -> QUEUED (scheduled release)
        // 3: QUEUED -> CANCELLED (tenant cancel recovery)
        List<NotificationStatusHistoryEntity> history = statusHistoryRepository
                .findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenant.tenantId(), notificationId);
        assertThat(history).hasSize(3);

        assertThat(history.get(0).getFromStatus()).isEqualTo(NotificationStatus.CREATED);
        assertThat(history.get(0).getToStatus()).isEqualTo(NotificationStatus.SCHEDULED);

        assertThat(history.get(1).getFromStatus()).isEqualTo(NotificationStatus.SCHEDULED);
        assertThat(history.get(1).getToStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(history.get(1).getActor()).isEqualTo(Actor.SYSTEM);

        assertThat(history.get(2).getFromStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(history.get(2).getToStatus()).isEqualTo(NotificationStatus.CANCELLED);
        assertThat(history.get(2).getActor()).isEqualTo(Actor.TENANT);

        // Exactly ONE cancellation history row!
        long cancelCount = history.stream().filter(h -> h.getToStatus() == NotificationStatus.CANCELLED).count();
        assertThat(cancelCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Scheduled-release vs tenant-cancel race: when release + processing claim win, tenant cancel fails 409 without partial writes")
    void cancelRace_releaseAndProcessingWin_tenantCancelFailsConflict() throws Exception {
        TenantFixture tenant = createTenant("ConflictRaceOrg");

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.IN_APP,
                "user-conflict",
                null,
                "Conflict notification",
                null, null, null, null, null,
                Instant.now().plus(1, ChronoUnit.HOURS)
        );
        MvcResult createRes = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn();

        UUID notificationId = UUID.fromString(objectMapper.readTree(createRes.getResponse().getContentAsString()).path("id").asText());

        // Release: SCHEDULED -> QUEUED (gen 0 -> 1)
        NotificationEntity entity = notificationRepository.findById(notificationId).orElseThrow();
        entity.setScheduledAt(Instant.now().minus(10, ChronoUnit.SECONDS));
        notificationRepository.save(entity);
        assertThat(notificationService.releaseScheduledNotification(tenant.tenantId(), notificationId, 0)).isTrue();

        // Worker claim: QUEUED -> PROCESSING (gen 1)
        notificationTransitioner.transition(
                tenant.tenantId(), notificationId, NotificationStatus.QUEUED, NotificationStatus.PROCESSING, Actor.SYSTEM, 1, "Worker claim"
        );

        // Tenant attempts cancel from SCHEDULED
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                notificationTransitioner.cancel(
                        tenant.tenantId(), notificationId, NotificationStatus.SCHEDULED, Actor.TENANT, 0, "Late cancel"
                ))
                .isInstanceOf(com.notify.common.error.NotifyException.class)
                .satisfies(ex -> {
                    com.notify.common.error.NotifyException ne = (com.notify.common.error.NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(com.notify.common.error.ErrorCode.INVALID_STATE_TRANSITION);
                    assertThat(ne.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                });

        // Entity remains in PROCESSING with no cancel partial writes
        NotificationEntity finalEntity = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(finalEntity.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(finalEntity.getCancelReason()).isNull();

        List<NotificationStatusHistoryEntity> history = statusHistoryRepository
                .findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenant.tenantId(), notificationId);
        // Only created, release, and claim rows exist (3 total); zero cancel rows
        assertThat(history).hasSize(3);
        assertThat(history.stream().noneMatch(h -> h.getToStatus() == NotificationStatus.CANCELLED)).isTrue();
    }

    @Test
    @DisplayName("Due-time fence: direct RETRYING -> PROCESSING claim fails with Conflict if next_retry_at is in the future")
    void retryingToProcessing_fencedByDueTime_whenNextRetryAtInFuture_failsWithConflict() throws Exception {
        TenantFixture tenant = createTenant("DueFenceOrg1");

        NotificationEntity entity = new NotificationEntity();
        entity.setTenantId(tenant.tenantId());
        entity.setChannel(Channel.EMAIL);
        entity.setStatus(NotificationStatus.RETRYING);
        entity.setRecipient("early-claim@example.com");
        entity.setSubject("Early Claim Subject");
        entity.setBody("Early Claim Body");
        entity.setDispatchGeneration(1);
        entity.setNextRetryAt(Instant.now().plus(60, ChronoUnit.SECONDS)); // in the future!
        entity = notificationRepository.save(entity);

        UUID nid = entity.getId();

        // Attempting direct claim from RETRYING -> PROCESSING while next_retry_at is in the future must fail!
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                notificationTransitioner.transition(
                        tenant.tenantId(), nid, NotificationStatus.RETRYING, NotificationStatus.PROCESSING, Actor.SYSTEM, 1, "Early worker claim"
                ))
                .isInstanceOf(com.notify.common.error.NotifyException.class)
                .satisfies(ex -> {
                    com.notify.common.error.NotifyException ne = (com.notify.common.error.NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(com.notify.common.error.ErrorCode.INVALID_STATE_TRANSITION);
                    assertThat(ne.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                });

        // Entity status remains RETRYING
        NotificationEntity after = notificationRepository.findById(nid).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(NotificationStatus.RETRYING);
    }

    @Test
    @DisplayName("Due-time fence: direct RETRYING -> PROCESSING claim succeeds when next_retry_at is due (<= now)")
    void retryingToProcessing_fencedByDueTime_whenNextRetryAtInPast_succeeds() throws Exception {
        TenantFixture tenant = createTenant("DueFenceOrg2");

        NotificationEntity entity = new NotificationEntity();
        entity.setTenantId(tenant.tenantId());
        entity.setChannel(Channel.EMAIL);
        entity.setStatus(NotificationStatus.RETRYING);
        entity.setRecipient("due-claim@example.com");
        entity.setSubject("Due Claim Subject");
        entity.setBody("Due Claim Body");
        entity.setDispatchGeneration(1);
        entity.setNextRetryAt(Instant.now().minus(5, ChronoUnit.SECONDS)); // due!
        entity = notificationRepository.save(entity);

        UUID nid = entity.getId();

        // When next_retry_at <= now, transition succeeds
        notificationTransitioner.transition(
                tenant.tenantId(), nid, NotificationStatus.RETRYING, NotificationStatus.PROCESSING, Actor.SYSTEM, 1, "Due worker claim"
        );

        NotificationEntity after = notificationRepository.findById(nid).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
    }
}
