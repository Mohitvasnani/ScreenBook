package com.mohit.screenbook.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * A 429. Carries how long to wait, both in the body and, through the handler,
 * in the Retry-After header that well behaved clients read.
 */
public class RateLimitedException extends ApiException {

    private final long retryAfterSeconds;

    public RateLimitedException(String detail, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, Problems.RATE_LIMITED, "Too many requests",
                detail, Map.of("retryAfterSeconds", retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}