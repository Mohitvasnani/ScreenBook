package com.mohit.screenbook.common;

import org.springframework.http.HttpStatus;

/** A 422 raised from a service, for rules no constraint annotation expresses. */
public class ValidationFailedException extends ApiException {

    public ValidationFailedException(String detail) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, Problems.VALIDATION_FAILED,
                "Validation failed", detail);
    }
}
