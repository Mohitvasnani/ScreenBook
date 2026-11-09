package com.mohit.screenbook.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Only the address. Nothing else is needed and nothing else would be trusted.
 */
public record ForgotPasswordRequest(
        @NotBlank(message = "is required")
        @Email(message = "must be a valid email address")
        String email
) {
}