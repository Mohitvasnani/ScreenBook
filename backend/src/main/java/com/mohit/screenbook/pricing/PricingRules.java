package com.mohit.screenbook.pricing;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * What a seat costs, and why.
 *
 * A pure function of its arguments. No Clock, no repository, no transaction:
 * now arrives as a parameter, which is what lets this be tested with plain
 * JUnit and no Testcontainers. Every other test class in this project needs
 * Docker; these do not.
 *
 * The rules are additive. Each one contributes a percentage of the listed
 * price and they are summed, rather than multiplied, so every line of the
 * breakdown is independently true. Multiplied, the figure beside a rule would
 * depend on which rules fired before it.
 */
@Component
public class PricingRules {

    /* Stored on hold_price_rule, so these strings are part of the data and
       renaming one is a migration, not a refactor. */
    static final String OCCUPANCY = "OCCUPANCY";
    static final String LEAD_TIME = "LEAD_TIME";
    static final String TIME_OF_DAY = "TIME_OF_DAY";
    static final String DAY_OF_WEEK = "DAY_OF_WEEK";
    static final String CAP = "CAP";

    private final PricingProperties config;

    PricingRules(PricingProperties config) {
        this.config = config;
    }

    /**
     * The listed price, the adjusted price, and one line per rule.
     *
     * Every rule returns a line even when it lands in a zero band, because
     * storing the bands that did not fire is what keeps "this rule did not
     * apply" distinguishable from "this rule did not exist when the hold was
     * taken". The API filters the zeros out; the database keeps them.
     */
    public PriceBreakdown priceFor(int basePriceMinor,
                                   int seatsTaken, int seatsTotal,
                                   Instant startsAt, ZoneId zone, Instant now) {

        /* Worked out once and passed down. Both time-based rules are questions
           about a local clock, and startsAt is an instant, which has no local
           time until a zone names one.

           The zone is the cinema's, not the application's, and it arrives as a
           parameter beside startsAt because the two are never useful apart. A
           single configured zone priced a 19:10 Liverpool screening by
           Kolkata's clock, where it is 00:40 and not an evening at all.

           zone and startsAt travelling as two loose parameters is the one
           compromise here. The honest shape is a small value type owned by
           this package: pricing cannot take catalogue's
           ShowtimePricingInputs without making a package cycle, because
           catalogue already depends on pricing. That is a refactor rather
           than part of this fix. */
        ZonedDateTime local = startsAt.atZone(zone);

        List<PriceRule> lines = new ArrayList<>(List.of(
                occupancyLine(basePriceMinor, seatsTaken, seatsTotal),
                leadTimeLine(basePriceMinor, startsAt, now),
                timeOfDayLine(basePriceMinor, local),
                dayOfWeekLine(basePriceMinor, local)));

        int totalBp = lines.stream().mapToInt(PriceRule::basisPoints).sum();
        int totalAdjustment = lines.stream().mapToInt(PriceRule::adjustmentMinor).sum();

        int ceiling = config.maxAdjustmentBp();
        if (totalBp > ceiling) {
            /* The cap is a line on the receipt, not a silent truncation.
               Its amount is the difference between the capped total and what
               the rules asked for, NOT applyBp of its own basis points.
               Deriving it as the difference is what keeps the lines summing
               exactly to the price charged; computing it independently would
               leave the receipt a rupee out whenever rounding disagreed. */
            int cappedAdjustment = applyBp(basePriceMinor, ceiling);
            lines.add(new PriceRule(CAP, "Capped",
                    ceiling - totalBp,
                    cappedAdjustment - totalAdjustment,
                    5));
            totalAdjustment = cappedAdjustment;
        }

        return new PriceBreakdown(
                basePriceMinor,
                basePriceMinor + totalAdjustment,
                List.copyOf(lines));
    }

    /**
     * How full the house is, as whole percent.
     *
     * Integer division, so it floors: 79.6 percent stays in the lower band
     * rather than rounding up into the higher one. Deterministic either way,
     * and flooring is the reading that favours the customer.
     *
     * The zero guard cannot fire, because the seat view produces no rows for a
     * screen with no seats, so nothing could have got this far. An
     * ArithmeticException would be a poor way to discover otherwise.
     */
    private PriceRule occupancyLine(int base, int seatsTaken, int seatsTotal) {
        PricingProperties.Occupancy band = config.occupancy();
        int percent = seatsTotal <= 0 ? 0 : seatsTaken * 100 / seatsTotal;

        if (percent >= band.nearlyFullThreshold()) {
            return line(OCCUPANCY, "Nearly full", band.nearlyFullBp(), base, 1);
        }
        if (percent >= band.halfFullThreshold()) {
            return line(OCCUPANCY, "Filling up", band.halfFullBp(), base, 1);
        }
        return line(OCCUPANCY, "Quiet", 0, base, 1);
    }

    /**
     * Hours until the screening starts.
     *
     * toHours truncates, so 47h59m is 47 and falls inside a 48 hour band while
     * exactly 48 hours does not. The labels are built from the configured
     * thresholds rather than written out, so a label cannot end up claiming a
     * boundary the config no longer has.
     *
     * A screening that already started gives negative hours and lands in the
     * imminent band. That is wrong but it is not this function's bug: nothing
     * stops a hold being claimed on a past showtime, and the fix belongs in
     * HoldService.claim rather than being papered over here.
     */
    private PriceRule leadTimeLine(int base, Instant startsAt, Instant now) {
        PricingProperties.LeadTime band = config.leadTime();
        long hours = Duration.between(now, startsAt).toHours();

        if (hours < band.imminentHours()) {
            return line(LEAD_TIME,
                    "Under %d hours to showtime".formatted(band.imminentHours()),
                    band.imminentBp(), base, 2);
        }
        if (hours < band.soonHours()) {
            return line(LEAD_TIME,
                    "Under %d hours to showtime".formatted(band.soonHours()),
                    band.soonBp(), base, 2);
        }
        return line(LEAD_TIME, "Plenty of time", 0, base, 2);
    }

    /** Inclusive at both ends, so a 22:59 window includes 22:59 and not 23:00. */
    private PriceRule timeOfDayLine(int base, ZonedDateTime local) {
        PricingProperties.TimeOfDay band = config.timeOfDay();
        LocalTime at = local.toLocalTime();

        boolean evening = !at.isBefore(band.eveningFrom())
                && !at.isAfter(band.eveningTo());

        return evening
                ? line(TIME_OF_DAY, "Evening show", band.eveningBp(), base, 3)
                : line(TIME_OF_DAY, "Daytime show", 0, base, 3);
    }

    private PriceRule dayOfWeekLine(int base, ZonedDateTime local) {
        PricingProperties.Weekend band = config.dayOfWeek();

        return band.weekendDays().contains(local.getDayOfWeek())
                ? line(DAY_OF_WEEK, "Weekend", band.weekendBp(), base, 4)
                : line(DAY_OF_WEEK, "Weekday", 0, base, 4);
    }

    /* One place where a line's money amount is worked out, so no band can
       forget to round the same way as the others. */
    private static PriceRule line(String code, String label,
                                  int basisPoints, int base, int sortOrder) {
        return new PriceRule(code, label, basisPoints,
                applyBp(base, basisPoints), sortOrder);
    }

    /**
     * Scales an amount by basis points, rounding half away from zero, in
     * integer arithmetic. 10000 basis points is 100%.
     *
     * The product is a long, not an int. A seat above roughly 5,370 rupees
     * times 4000 basis points overflows a 32-bit int, and a silent wraparound
     * in a price is the worst failure mode available.
     *
     * Negative basis points are real: a discount band, and the cap line.
     */
    static int applyBp(int baseMinor, int basisPoints) {
        long n = (long) baseMinor * basisPoints;
        return (int) (n >= 0 ? (n + 5_000) / 10_000 : -((-n + 5_000) / 10_000));
    }
}