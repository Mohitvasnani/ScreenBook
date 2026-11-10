package com.mohit.screenbook.catalogue;

import java.time.Instant;
import java.time.ZoneId;

/**
 * One row of the upcoming-showtimes query. Internal: ShowtimeSummary is the
 * JSON shape.
 *
 * baseFromPriceMinor is the cheapest listed seat in the house for this
 * screening, which is the lowest row in the price table for its hall class.
 * The service turns that into the cheapest price actually on sale.
 *
 * seatsTaken rather than seatsAvailable, because that is what
 * showtime_occupancy reports and what the pricing rules want. The subtraction
 * happens once, in the service, next to the thing that needs it.
 */
public record ShowtimeRow(
        long id,
        long cinemaId,
        String filmTitle,
        String screenName,
        Instant startsAt,
        /* The cinema's zone, for the pricing rules. Not carried onto
           ShowtimeSummary: a listing is already scoped to one cinema, so the
           client reads the zone once from the cinema rather than on every
           row. */
        ZoneId zone,
        int runtimeMinutes,
        int baseFromPriceMinor,
        String currency,
        int seatsTaken,
        int seatsTotal
) {
}