package com.mohit.screenbook.pricing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
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

        /**
         * Which zone decides whether a show is an evening or a weekend one.
         * starts_at is an instant, so without an explicit zone this reads the
         * server default and a container running UTC disagrees with a laptop.
         * Same class of bug as the Locale.ROOT fix in Emails.
         */
        ZoneId zone,

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