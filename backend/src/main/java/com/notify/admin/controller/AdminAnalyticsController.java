package com.notify.admin.controller;

import com.notify.admin.dto.AdminAnalyticsOverviewResponse;
import com.notify.admin.security.AdminSecurityUtils;
import com.notify.admin.service.AdminAnalyticsService;
import com.notify.analytics.dto.UsageSummaryResponse;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Platform admin analytics endpoints (/ ). Strict admin boundary: {@code ROLE_PLATFORM_ADMIN} and
 * {@code aud=notify-admin} (chain + method security + principal check).
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AdminAnalyticsController {

    private static final Duration DEFAULT_WINDOW = Duration.ofDays(30);

    private final AdminAnalyticsService adminAnalyticsService;
    private final Clock clock;

    public AdminAnalyticsController(AdminAnalyticsService adminAnalyticsService, Clock clock) {
        this.adminAnalyticsService = adminAnalyticsService;
        this.clock = clock;
    }

    @GetMapping("/analytics/overview")
    public ResponseEntity<AdminAnalyticsOverviewResponse> overview(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            Authentication authentication) {
        AdminSecurityUtils.requireAdminPrincipal(authentication);
        Instant toInstant = parseOrDefault(to, "to", clock.instant());
        Instant fromInstant = parseOrDefault(from, "from", toInstant.minus(DEFAULT_WINDOW));
        requireOrdered(fromInstant, toInstant);
        return ResponseEntity.ok(adminAnalyticsService.overview(fromInstant, toInstant));
    }

    @GetMapping("/tenants/{tenantId}/usage")
    public ResponseEntity<UsageSummaryResponse> tenantUsage(
            @PathVariable("tenantId") UUID tenantId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            Authentication authentication) {
        AdminSecurityUtils.requireAdminPrincipal(authentication);
        Instant toInstant = parseOrDefault(to, "to", clock.instant());
        Instant fromInstant = parseOrDefault(from, "from", toInstant.minus(DEFAULT_WINDOW));
        requireOrdered(fromInstant, toInstant);
        return ResponseEntity.ok(adminAnalyticsService.tenantUsage(tenantId, fromInstant, toInstant));
    }

    private Instant parseOrDefault(String raw, String field, Instant fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException e) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, field + " must be an ISO-8601 instant");
        }
    }

    private void requireOrdered(Instant from, Instant to) {
        if (from.isAfter(to)) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, "from must not be after to");
        }
    }
}
