package com.mohit.screenbook.booking;

import java.util.UUID;

/**
 * A booking has committed. Carries only what the transaction knew; the
 * listener reads the booking back, so the event cannot disagree with the row.
 */
public record BookingConfirmed(String reference, UUID userId, String email) {
}