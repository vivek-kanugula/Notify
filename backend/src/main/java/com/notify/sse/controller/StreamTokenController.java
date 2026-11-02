package com.notify.sse.controller;

import com.notify.apikey.cache.ApiKeyStatusCacheService;
import com.notify.common.context.TenantPrincipal;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.TenantStatus;
import com.notify.sse.config.SseProperties;
import com.notify.sse.stream.SseStreamCapService;
import com.notify.sse.token.StreamTokenService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Mints single-use SSE stream tokens (CONTRACTS §SSE; / , ).
 *
 * <p><b>This endpoint is the browser's readiness and backoff channel (condition C2).</b>
 * {@code EventSource.onerror} exposes no HTTP status, no {@code Retry-After} and no response body, so a client cannot
 * learn from the stream why it was refused. It therefore asks here first: this is an ordinary authenticated JSON
 * endpoint, so a refusal is a real {@code 429 STREAM_LIMIT_REACHED} with a readable {@code retryAfterSeconds} —
 * covering both the per-tenant cap and the ≈95 s provenance barrier. The authoritative checks remain on the stream
 * connection itself: a token is still consumed atomically there, and an expired or already-used token still fails
 * closed regardless of what this endpoint said.
 */
@RestController
@RequestMapping("/api/v1/inapp")
public class StreamTokenController {

    private final StreamTokenService tokens;
    private final SseStreamCapService cap;
    private final ApiKeyStatusCacheService tenantStatus;
    private final SseProperties props;

    public StreamTokenController(
            StreamTokenService tokens,
            SseStreamCapService cap,
            ApiKeyStatusCacheService tenantStatus,
            SseProperties props
    ) {
        this.tokens = tokens;
        this.cap = cap;
        this.tenantStatus = tenantStatus;
        this.props = props;
    }

    public record StreamTokenRequest(String recipientRef) {
    }

    @PostMapping("/stream-tokens")
    public ResponseEntity<Map<String, Object>> mint(@RequestBody(required = false) StreamTokenRequest request) {
        TenantPrincipal principal = InAppStreamSupport.requireStreamAuthority();
        String recipientRef = InAppStreamSupport.requireRecipientRef(
                request == null ? null : request.recipientRef());
        assertTenantActive(principal.tenantId());

        // The readable refusal (condition C2). Until the corrective cycle this endpoint ALWAYS minted, so
        // the browser could never learn the cap or the ≈95 s barrier from anywhere: the stream reports a refusal
        // only as an EventSource error with no status, no Retry-After and no body, and the client's own refusal
        // handling was unreachable. The check is read-only - it adds no member and consumes no slot - and the
        // authoritative decision stays on the stream connection, which still admits atomically and fails closed.
        SseStreamCapService.Readiness readiness = cap.readiness(principal.tenantId());
        if (!readiness.ready()) {
            long retryAfterSeconds = Math.max(1, (readiness.retryAfterMillis() + 999) / 1000);
            return ResponseEntity.status(ErrorCode.STREAM_LIMIT_REACHED.getDefaultStatus())
                    .header("Retry-After", Long.toString(retryAfterSeconds))
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(Map.of(
                            "type", "https://notify.dev/errors/stream-limit-reached",
                            "title", ErrorCode.STREAM_LIMIT_REACHED.name(),
                            "status", ErrorCode.STREAM_LIMIT_REACHED.getDefaultStatus().value(),
                            "detail", "No stream slot is available for this tenant (" + readiness.reason() + ")",
                            "retryAfterSeconds", retryAfterSeconds));
        }

        Optional<String> token = tokens.mint(principal.tenantId(), recipientRef);
        if (token.isEmpty()) {
            // Fail-closed: without Redis a token can be neither stored nor verified, so no stream may open.
            throw new NotifyException(ErrorCode.FEATURE_NOT_YET_AVAILABLE,
                    "Stream tokens are temporarily unavailable");
        }
        return ResponseEntity.ok(Map.of(
                "token", token.get(),
                "expiresInSeconds", props.tokenTtl().toSeconds(),
                "streamUrl", "/sse/inapp",
                "admittedStreams", cap.admittedCount(principal.tenantId()),
                "capPerTenant", props.capPerTenant()));
    }

    private void assertTenantActive(UUID tenantId) {
        TenantStatus status = tenantStatus.getTenantStatus(tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Tenant not found"));
        if (status == TenantStatus.SUSPENDED) {
            throw new NotifyException(ErrorCode.TENANT_SUSPENDED, "Tenant is suspended (read-only mode)");
        }
        if (status == TenantStatus.REVOKED) {
            throw new NotifyException(ErrorCode.TENANT_REVOKED, "Tenant is revoked");
        }
    }
}
