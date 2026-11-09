package com.mohit.screenbook.common;

import org.springframework.http.HttpStatus;

/**
 * 422 rather than 401, because nobody is authenticating: this is a link that
 * has expired, been used, or never existed. One exception for all three, so a
 * caller cannot learn which, and copy that tells someone what to do next
 * rather than what went wrong.
 */
public class InvalidResetTokenException extends ApiException {

    public InvalidResetTokenException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, Problems.INVALID_RESET_TOKEN,
                "Invalid reset link",
                "That reset link is invalid or has expired. Request a new one.");
    }
}