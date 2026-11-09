package com.mohit.screenbook.hold;

import com.mohit.screenbook.pricing.PricingView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * expiresAt is authoritative and is what gets displayed. ttlSeconds lets the
 * browser count down against its own clock, so a user whose machine clock is
 * minutes out does not see a timer that starts at zero. One deadline for the
 * whole hold, not one per seat: a hold expires whole and converts whole.
 *
 * seats is ordered by row then seat number, which is how a customer reads a
 * ticket, rather than the order they happened to tap.
 *
 * totalMinor is the sum of the seat prices and pricing explains how one seat
 * got to its price. Both are computed server side for the same reason: the
 * client should never be the thing that decides what a basket costs.
 *
 * Every price here is frozen. It is the number quoted at the moment of the
 * claim, not today's, which is what lets the checkout screen promise the
 * price is held.
 */
public record HoldResponse(
        UUID holdId,
        long showtimeId,
        List<HeldSeatView> seats,
        int totalMinor,
        String currency,
        PricingView pricing,
        Instant expiresAt,
        long ttlSeconds
) {
}