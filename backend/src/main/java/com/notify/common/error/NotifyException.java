package com.notify.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base application exception carrying a frozen ErrorCode and HttpStatus.
 */
public class NotifyException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus status;

    public NotifyException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = errorCode.getDefaultStatus();
    }

    public NotifyException(ErrorCode errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public NotifyException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.status = errorCode.getDefaultStatus();
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
