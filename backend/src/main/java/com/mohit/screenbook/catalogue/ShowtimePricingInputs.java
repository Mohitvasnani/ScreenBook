package com.mohit.screenbook.catalogue;

import java.time.Instant;

/**
 * Everything the pricing rules need to know about a screening, in one read.
 * Not exposed over HTTP.
 */
public record ShowtimePricingInputs(int seatsTaken, int seatsTotal, Instant startsAt) {
}