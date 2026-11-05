package com.notify.admin;

import com.notify.admin.controller.AdminAnalyticsController;
import com.notify.admin.dto.AdminAnalyticsOverviewResponse;
import com.notify.admin.service.AdminAnalyticsService;
import com.notify.analytics.service.UsageService;
import com.notify.common.model.NotificationStatus;
import com.notify.notification.service.NotificationQueryService;
import com.notify.notification.service.NotificationQueryService.LatencyPercentiles;
import com.notify.notification.service.NotificationQueryService.StatusCount;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminAnalyticsUnitTest {

    private NotificationQueryService notificationQueryService;
    private UsageService usageService;
    private TenantRepository tenantRepository;
    private AdminAnalyticsService adminAnalyticsService;
    private AdminAnalyticsController adminAnalyticsController;

    @BeforeEach
    void setUp() {
        notificationQueryService = mock(NotificationQueryService.class);
        usageService = mock(UsageService.class);
        tenantRepository = mock(TenantRepository.class);
        adminAnalyticsService = new AdminAnalyticsService(notificationQueryService, usageService, tenantRepository);
        adminAnalyticsController = new AdminAnalyticsController(adminAnalyticsService, java.time.Clock.systemUTC());
    }

    @Test
    @DisplayName("overview maps each status to statusCounts and calculates active/delivered counts accurately")
    void overviewCalculatesStatusCountsAccurately() {
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-05T00:00:00Z");

        when(notificationQueryService.statusCountsGlobal(from, to)).thenReturn(List.of(
                new StatusCount(NotificationStatus.DELIVERED, 10L),
                new StatusCount(NotificationStatus.QUEUED, 3L),
                new StatusCount(NotificationStatus.PROCESSING, 0L),
                new StatusCount(NotificationStatus.FAILED, 1L),
                new StatusCount(NotificationStatus.DLQ, 1L)
        ));
        when(notificationQueryService.latencyPercentilesGlobal(from, to))
                .thenReturn(new LatencyPercentiles(50, 100, 200));
        when(notificationQueryService.topTenantsByVolume(any(), any(), anyInt()))
                .thenReturn(List.of());
        when(notificationQueryService.topFailureReasons(any(), any(), anyInt()))
                .thenReturn(List.of());
        when(notificationQueryService.queueDepthGlobal()).thenReturn(3L);
        when(notificationQueryService.dlqCountGlobal()).thenReturn(1L);

        AdminAnalyticsOverviewResponse response = adminAnalyticsService.overview(from, to);

        assertThat(response.totalNotifications()).isEqualTo(15L);
        assertThat(response.deliveredCount()).isEqualTo(10L);
        assertThat(response.failedCount()).isEqualTo(2L); // FAILED (1) + DLQ (1)
        assertThat(response.activeCount()).isEqualTo(3L);  // QUEUED (3)
        assertThat(response.queueDepth()).isEqualTo(3L);
        assertThat(response.dlqOpenCount()).isEqualTo(1L);

        Map<String, Long> statusCounts = response.statusCounts();
        assertThat(statusCounts).isNotNull();
        assertThat(statusCounts.get("DELIVERED")).isEqualTo(10L);
        assertThat(statusCounts.get("QUEUED")).isEqualTo(3L);
        assertThat(statusCounts.get("PROCESSING")).isEqualTo(0L);
        assertThat(statusCounts.get("SCHEDULED")).isEqualTo(0L);
        assertThat(statusCounts.get("RETRYING")).isEqualTo(0L);
        assertThat(statusCounts.get("FAILED")).isEqualTo(1L);
        assertThat(statusCounts.get("DLQ")).isEqualTo(1L);
        assertThat(statusCounts.get("CANCELLED")).isEqualTo(0L);

        Map<String, Object> principal = Map.of(
                "sub", java.util.UUID.randomUUID().toString(),
                "aud", "notify-admin"
        );
        org.springframework.security.core.Authentication auth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        principal, null,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")));

        ResponseEntity<AdminAnalyticsOverviewResponse> controllerResponse =
                adminAnalyticsController.overview(from.toString(), to.toString(), auth);
        assertThat(controllerResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controllerResponse.getBody()).isEqualTo(response);
    }
}

