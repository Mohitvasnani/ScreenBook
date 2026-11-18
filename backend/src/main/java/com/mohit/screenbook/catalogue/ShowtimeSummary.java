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
 * so twelve rows would carry up to sixty explanation lines nobody reads, and
 * the two prices side by side already say what a list needs to say. The
 * reasons belong on the seat map and the checkout.
 *
 * posterPath is TMDB's path, like /abc123.jpg, not a URL. The client builds
 * the URL and picks the size, because a listing thumbnail and a seat map
 * header want different ones and baking w342 in here would make changing a
 * thumbnail an API change. Nullable: film.poster_path has no NOT NULL, so the
 * client needs a placeholder.
 *
 * releasedOn is deliberately not here. It is the fifth pricing rule's input,
 * and the client is shown the price the rule produced, not the input.
 */
public record ShowtimeSummary(
        long id,
        long cinemaId,
        String filmTitle,
        String posterPath,
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