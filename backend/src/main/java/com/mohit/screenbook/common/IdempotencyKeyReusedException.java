package com.mohit.screenbook.common;

import org.springframework.http.HttpStatus;

/** The same key sent with a different request. A client bug, so it is reported. */
public class IdempotencyKeyReusedException extends ApiException {

    public IdempotencyKeyReusedException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, Problems.IDEMPOTENCY_KEY_REUSED,
                "Idempotency key reused",
                "That Idempotency-Key was already used for a different request. Use a new key.");
    }
}
