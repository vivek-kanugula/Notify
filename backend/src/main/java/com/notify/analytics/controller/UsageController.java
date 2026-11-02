package com.notify.analytics.controller;

import com.notify.analytics.dto.UsageSummaryResponse;
import com.notify.analytics.dto.UsageTimeseriesResponse;
import com.notify.analytics.service.UsageService;
import com.notify.common.context.TenantContext;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Tenant-scoped business usage analytics (/ ). Tenant identity comes from the verified
 * {@link TenantContext}, never the request path/body. Secured by the default chain
 * ({@code /api/v1/**} requires an authenticated tenant and excludes PLATFORM_ADMIN).
 */
@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {

    private static final Duration DEFAULT_WINDOW = Duration.ofDays(30);

    private final UsageService usageService;
    private final Clock clock;

    public UsageController(UsageService usageService, Clock clock) {
        this.usageService = usageService;
        this.clock = clock;
    }

    @GetMapping("/summary")
    public ResponseEntity<UsageSummaryResponse> summary(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        UUID tenantId = TenantContext.require().tenantId();
        Instant toInstant = parseOrDefault(to, "to", clock.instant());
        Instant fromInstant = parseOrDefault(from, "from", toInstant.minus(DEFAULT_WINDOW));
        requireOrdered(fromInstant, toInstant);
        return ResponseEntity.ok(usageService.summary(tenantId, fromInstant, toInstant));
    }

    @GetMapping("/timeseries")
    public ResponseEntity<UsageTimeseriesResponse> timeseries(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "interval", defaultValue = "day") String interval) {
        UUID tenantId = TenantContext.require().tenantId();
        Instant toInstant = parseOrDefault(to, "to", clock.instant());
        Instant fromInstant = parseOrDefault(from, "from", toInstant.minus(DEFAULT_WINDOW));
        requireOrdered(fromInstant, toInstant);
        return ResponseEntity.ok(usageService.timeseries(tenantId, fromInstant, toInstant, interval));
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
