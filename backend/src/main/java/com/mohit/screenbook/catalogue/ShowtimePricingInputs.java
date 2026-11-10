package com.mohit.screenbook.catalogue;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Everything the pricing rules need to know about a screening, in one read.
 * Not exposed over HTTP.
 *
 * zone is the cinema's, reached through the screen. startsAt is an instant and
 * the evening and weekend rules are questions about a local clock, so the two
 * are only ever useful together and are read together.
 */
public record ShowtimePricingInputs(int seatsTaken, int seatsTotal,
                                    Instant startsAt, ZoneId zone) {
}