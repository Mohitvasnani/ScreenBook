package com.mohit.screenbook.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The token proves who you are, so there is no address here. Asking for one
 * would be asking a question whose answer we would have to ignore: the token
 * already names the account, and trusting a supplied address would let anyone
 * with any valid token reset anybody.
 *
 * The password bounds match signup. The 72 ceiling is BCrypt's, which ignores
 * everything past 72 bytes, so a longer password would have a silent dead tail.
 */
public record ResetPasswordRequest(
        @NotBlank(message = "is required") String token,

        @NotBlank(message = "is required")
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        String password
) {
}