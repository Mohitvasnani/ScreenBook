package com.mohit.screenbook.catalogue;

import java.time.Instant;

/**
 * One row of GET /api/showtimes. Field names are the JSON keys, so this file
 * and docs/api-contract.md have to agree.
 *
 * priceMinor is the cheapest seat actually on sale for this screening, after
 * the rules. basePriceMinor is what that seat is listed at. Both are sent so
 * a list can say "higher than usual" without doing the arithmetic.
 *
 * No rule breakdown here on purpose. Each screening has its own rules firing,
 * so twelve rows would carry up to forty-eight explanation lines nobody reads,
 * and the two prices side by side already say what a list needs to say. The
 * reasons belong on the seat map and the checkout.
 */
public record ShowtimeSummary(
        long id,
        String filmTitle,
        String screenName,
        Instant startsAt,
        int runtimeMinutes,
        int basePriceMinor,
        int priceMinor,
        String currency,
        int seatsAvailable,
        int seatsTotal
) {
}