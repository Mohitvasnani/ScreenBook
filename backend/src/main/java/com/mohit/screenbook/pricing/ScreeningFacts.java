package com.mohit.screenbook.pricing;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Everything about a screening that changes what a seat costs.
 *
 * This exists because priceFor had grown to six positional parameters, five of
 * which described the screening, and two of those were Instants that would
 * have compiled perfectly well transposed. Adding released_on would have made
 * it seven. A record cannot be called wrongly: the compiler checks the names.
 *
 * Owned by pricing, not by catalogue, and that direction is the whole reason
 * this was deferred once. catalogue already depends on pricing, so pricing
 * cannot import ShowtimePricingInputs without a package cycle. Declaring the
 * record here and letting catalogue map into it points the arrow the way it
 * already points.
 *
 * releasedOn is nullable, because film.released_on is nullable with no CHECK
 * and TMDB does not have a date for every film. A null is not a zero: it means
 * nobody knows how old this film is, which is not the same as it being new.
 *
 * zone travels beside startsAt because an instant has no local time until a
 * zone names one, and the zone that decides "evening" belongs to the building.
 */
public record ScreeningFacts(int seatsTaken, int seatsTotal,
                             Instant startsAt, ZoneId zone,
                             LocalDate releasedOn) {
}