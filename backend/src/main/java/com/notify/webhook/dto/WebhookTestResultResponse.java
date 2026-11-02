package com.notify.webhook.dto;

import com.notify.webhook.service.WebhookPingSender;

/** Sanitized outcome of the single test ping (): no response body, no headers, no secret. */
public record WebhookTestResultResponse(
        boolean delivered,
        Integer httpStatus,
        String outcome,
        String errorCode,
        String detail
) {
    public static WebhookTestResultResponse from(WebhookPingSender.PingResult result) {
        return new WebhookTestResultResponse(result.delivered(), result.httpStatus(), result.outcome(),
                result.errorCode(), result.detail());
    }
}
