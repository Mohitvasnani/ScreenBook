package com.mohit.screenbook.hold;

import com.mohit.screenbook.pricing.PricingView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * HoldResponse plus the hold's current state, for GET /api/holds/{holdId}.
 * One status for the whole hold, not one per seat.
 *
 * ttlSeconds here is the time actually remaining, not the full TTL, so a
 * checkout screen reloaded four minutes in shows a minute left rather than
 * five.
 *
 * The prices and the breakdown are the ones frozen at claim time, so a reload
 * shows the same figures as the original quote even if the house has filled
 * since.
 */
public record HoldDetailResponse(
        UUID holdId,
        long showtimeId,
        List<HeldSeatView> seats,
        int totalMinor,
        String currency,
        PricingView pricing,
        Instant expiresAt,
        long ttlSeconds,
        HoldStatus status
) {
}