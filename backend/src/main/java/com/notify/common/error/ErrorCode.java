package com.notify.common.error;

import org.springframework.http.HttpStatus;

/**
 * Frozen error codes defined in Notify CONTRACTS.md.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_CONTENT),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    INVALID_API_KEY(HttpStatus.UNAUTHORIZED),
    API_KEY_EXPIRED(HttpStatus.UNAUTHORIZED),
    INSUFFICIENT_SCOPE(HttpStatus.FORBIDDEN),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    TENANT_SUSPENDED(HttpStatus.FORBIDDEN),
    TENANT_REVOKED(HttpStatus.FORBIDDEN),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT),
    SCHEDULE_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_CONTENT),
    TEMPLATE_VARIABLE_MISSING(HttpStatus.UNPROCESSABLE_CONTENT),
    TEMPLATE_VERSION_IMMUTABLE(HttpStatus.CONFLICT),
    API_KEY_LIMIT_REACHED(HttpStatus.CONFLICT),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT),
    REFRESH_REUSE_DETECTED(HttpStatus.UNAUTHORIZED),
    STREAM_TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
    STREAM_LIMIT_REACHED(HttpStatus.TOO_MANY_REQUESTS),
    MISSING_REQUEST_HEADER(HttpStatus.BAD_REQUEST),
    FEATURE_NOT_YET_AVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus defaultStatus;

    ErrorCode(HttpStatus defaultStatus) {
        this.defaultStatus = defaultStatus;
    }

    public HttpStatus getDefaultStatus() {
        return defaultStatus;
    }
}
