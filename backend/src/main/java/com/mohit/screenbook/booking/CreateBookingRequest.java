package com.mohit.screenbook.booking;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Only the hold id identifies what is being booked. The hold already knows its
 * seats and showtime, so re-sending them would allow a combination that
 * disagrees with the server.
 *
 * email is optional and exists for guests, who otherwise have nothing but the
 * reference. A signed-in booking is found by its user instead.
 */
public record CreateBookingRequest(
        @NotNull(message = "is required") UUID holdId,

        @Email(message = "must be a valid email address")
        @Size(max = 254, message = "is too long")
        String email
) {
}