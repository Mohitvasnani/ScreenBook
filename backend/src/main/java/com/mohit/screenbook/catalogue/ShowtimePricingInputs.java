package com.mohit.screenbook.catalogue;

import com.mohit.screenbook.pricing.ScreeningFacts;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Everything the pricing rules need to know about a screening, in one read.
 * Not exposed over HTTP.
 *
 * zone is the cinema's, reached through the screen. startsAt is an instant and
 * the evening and weekend rules are questions about a local clock, so the two
 * are only ever useful together and are read together.
 *
 * releasedOn is the film's, reached through the screening, and is nullable.
 *
 * This record and ShowtimeRow both exist because the two reads return
 * different shapes, but both of them can state the same facts to pricing. The
 * mapping lives here rather than at each call site, so there is one place that
 * knows which column feeds which field.
 */
public record ShowtimePricingInputs(int seatsTaken, int seatsTotal,
                                    Instant startsAt, ZoneId zone,
                                    LocalDate releasedOn) {

    public ScreeningFacts facts() {
        return new ScreeningFacts(seatsTaken, seatsTotal, startsAt, zone, releasedOn);
    }
}