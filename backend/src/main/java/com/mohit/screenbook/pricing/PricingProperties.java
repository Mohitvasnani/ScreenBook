package com.mohit.screenbook.pricing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

/**
 * The band configuration. Externalised so the demo can be tuned without a
 * rebuild, and so the pricing tests can construct their own values rather than
 * reading these. A test that reads production config breaks when you tune the
 * demo, which makes tuning the demo frightening.
 */
@ConfigurationProperties(prefix = "screenbook.pricing")
public record PricingProperties(
        Occupancy occupancy,
        LeadTime leadTime,
        TimeOfDay timeOfDay,
        Weekend dayOfWeek,

        /* No zone here. Which zone decides whether a show is an evening or a
           weekend one is a fact about the cinema, not about the application,
           so it arrives at priceFor as a parameter and comes from
           cinema.timezone. It is deliberately not left here as a default: a
           default would let a new call site reintroduce the bug silently,
           whereas its absence makes the compiler name every call site. */

        /**
         * Ceiling on the total adjustment. A backstop, not part of the model:
         * the four rules sum to exactly this, so it cannot fire with the
         * shipped config. It exists so a mistyped band cannot produce a
         * four-figure ticket, and because a legal ceiling has to be
         * expressible somewhere. Several Indian states cap ticket prices.
         */
        int maxAdjustmentBp
) {
    /** Thresholds are whole percents of the house. */
    public record Occupancy(int halfFullBp, int nearlyFullBp,
                            int halfFullThreshold, int nearlyFullThreshold) {
    }

    /** Hours until the screening starts. */
    public record LeadTime(int soonBp, int imminentBp,
                           int soonHours, int imminentHours) {
    }

    /** Local clock times, inclusive at both ends. */
    public record TimeOfDay(int eveningBp, LocalTime eveningFrom, LocalTime eveningTo) {
    }

    public record Weekend(int weekendBp, Set<DayOfWeek> weekendDays) {
    }
}