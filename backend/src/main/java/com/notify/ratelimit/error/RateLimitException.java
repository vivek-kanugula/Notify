package com.notify.ratelimit.error;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import org.springframework.http.HttpStatus;

/**
 * Exception thrown when tenant or IP rate limit is exhausted.
 */
public class RateLimitException extends NotifyException {

    private final long retryAfterSeconds;
    private final int limit;
    private final int remaining;
    private final long resetSeconds;

    public RateLimitException(long retryAfterSeconds) {
        this(retryAfterSeconds, 0, 0, retryAfterSeconds);
    }

    public RateLimitException(long retryAfterSeconds, int limit, int remaining, long resetSeconds) {
        super(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded. Try again in " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
        this.limit = limit;
        this.remaining = remaining;
        this.resetSeconds = resetSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public int getLimit() {
        return limit;
    }

    public int getRemaining() {
        return remaining;
    }

    public long getResetSeconds() {
        return resetSeconds;
    }
}
