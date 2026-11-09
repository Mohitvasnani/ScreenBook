package com.mohit.screenbook.pricing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pricing rules, in isolation.
 *
 * No @SpringBootTest, no Testcontainers, no Docker. priceFor is a pure
 * function of its arguments and now arrives as a parameter, so these run in
 * milliseconds. They are the only fast tests in the project.
 *
 * Config is built here rather than read from application.properties on
 * purpose. A test that reads production config breaks the moment you tune a
 * band for the demo, which makes tuning the demo frightening.
 *
 * Every case isolates one rule by keeping the other three in their zero band:
 * an empty house, a Wednesday, mid-afternoon, three days out.
 */
class PricingRulesTest {

    /** 200 rupees in paise. */
    private static final int BASE = 20_000;
    private static final int SEATS = 120;

    /** Wednesday 11 November 2026, 14:00 in Asia/Kolkata, which is 08:30 UTC. */
    private static final Instant WED_AFTERNOON = Instant.parse("2026-11-11T08:30:00Z");

    private static PricingRules rules() {
        return rules(4000);
    }

    private static PricingRules rules(int maxAdjustmentBp) {
        return new PricingRules(new PricingProperties(
                new PricingProperties.Occupancy(1000, 2000, 50, 80),
                new PricingProperties.LeadTime(500, 1000, 48, 6),
                new PricingProperties.TimeOfDay(
                        500, LocalTime.of(17, 0), LocalTime.of(22, 59)),
                new PricingProperties.Weekend(
                        500, EnumSet.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)),
                ZoneId.of("Asia/Kolkata"),
                maxAdjustmentBp));
    }

    private static Instant daysBefore(Instant startsAt, int days) {
        return startsAt.minus(Duration.ofHours(24L * days));
    }

    private static int bpOf(PriceBreakdown breakdown, String code) {
        return breakdown.rules().stream()
                .filter(rule -> rule.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no rule line with code " + code))
                .basisPoints();
    }

    /**
     * The invariant that matters most, asserted on every case below. If the
     * lines do not sum to the price, the receipt shows arithmetic that does
     * not work, and that is true whether the cause is a rounding mistake or
     * the cap.
     */
    private static void assertAddsUp(PriceBreakdown breakdown) {
        int sum = breakdown.rules().stream()
                .mapToInt(PriceRule::adjustmentMinor)
                .sum();
        assertEquals(breakdown.adjustedPriceMinor(),
                breakdown.basePriceMinor() + sum,
                "base plus every line must equal the adjusted price");
    }

    // ------------------------------------------------------------- baseline

    @Test
    @DisplayName("an empty midweek matinee three days out costs the listed price")
    void neutralCaseIsUnchanged() {
        PriceBreakdown b = rules().priceFor(
                BASE, 0, SEATS, WED_AFTERNOON, daysBefore(WED_AFTERNOON, 3));

        assertEquals(BASE, b.adjustedPriceMinor());
        assertEquals(0, bpOf(b, PricingRules.OCCUPANCY));
        assertEquals(0, bpOf(b, PricingRules.LEAD_TIME));
        assertEquals(0, bpOf(b, PricingRules.TIME_OF_DAY));
        assertEquals(0, bpOf(b, PricingRules.DAY_OF_WEEK));
        assertAddsUp(b);
    }

    @Test
    @DisplayName("every rule produces a line even when it does not fire")
    void zeroBandsStillProduceLines() {
        PriceBreakdown b = rules().priceFor(
                BASE, 0, SEATS, WED_AFTERNOON, daysBefore(WED_AFTERNOON, 3));

        /* Four lines, not zero. Storing the bands that did not fire is what
           keeps "this rule did not apply" distinguishable from "this rule did
           not exist when the hold was taken". The API filters the zeros. */
        assertEquals(4, b.rules().size());
    }

    // ------------------------------------------------------------ occupancy

    private int occupancyBpFor(int seatsTaken) {
        return bpOf(rules().priceFor(BASE, seatsTaken, SEATS,
                WED_AFTERNOON, daysBefore(WED_AFTERNOON, 3)), PricingRules.OCCUPANCY);
    }

    @Test
    @DisplayName("occupancy bands, either side of 50 and 80 percent")
    void occupancyBands() {
        assertEquals(0,    occupancyBpFor(59),  "49 percent is quiet");
        assertEquals(1000, occupancyBpFor(60),  "50 percent is filling up");
        assertEquals(1000, occupancyBpFor(95),  "79 percent is still filling up");
        assertEquals(2000, occupancyBpFor(96),  "80 percent is nearly full");
        assertEquals(2000, occupancyBpFor(120), "a full house stays in the top band");
    }

    @Test
    @DisplayName("a screen with no seats does not divide by zero")
    void emptyScreenIsNotAnError() {
        PriceBreakdown b = rules().priceFor(
                BASE, 0, 0, WED_AFTERNOON, daysBefore(WED_AFTERNOON, 3));

        assertEquals(0, bpOf(b, PricingRules.OCCUPANCY));
        assertAddsUp(b);
    }

    // ------------------------------------------------------------ lead time

    private int leadTimeBpFor(Duration until) {
        return bpOf(rules().priceFor(BASE, 0, SEATS,
                WED_AFTERNOON, WED_AFTERNOON.minus(until)), PricingRules.LEAD_TIME);
    }

    @Test
    @DisplayName("lead time bands, either side of 48 and 6 hours")
    void leadTimeBands() {
        assertEquals(0,    leadTimeBpFor(Duration.ofHours(48)),
                "exactly 48 hours is outside the band");
        assertEquals(500,  leadTimeBpFor(Duration.ofHours(47).plusMinutes(59)),
                "a minute inside 48 hours is in the band");
        assertEquals(500,  leadTimeBpFor(Duration.ofHours(6)),
                "exactly 6 hours is still the outer band");
        assertEquals(1000, leadTimeBpFor(Duration.ofHours(5).plusMinutes(59)),
                "a minute inside 6 hours is imminent");
    }

    // ---------------------------------------------------------- time of day

    private int timeOfDayBpFor(String startsAt) {
        Instant start = Instant.parse(startsAt);
        return bpOf(rules().priceFor(BASE, 0, SEATS, start, daysBefore(start, 3)),
                PricingRules.TIME_OF_DAY);
    }

    /**
     * These are the cases that fail if the zone is wrong. 11:30Z is not an
     * evening in UTC and is 17:00 in Asia/Kolkata, so a bare
     * LocalTime.ofInstant or a system default zone gives the opposite answer.
     */
    @Test
    @DisplayName("evening window is read in the configured zone, not UTC")
    void timeOfDayBands() {
        assertEquals(0,   timeOfDayBpFor("2026-11-11T11:29:00Z"), "16:59 local");
        assertEquals(500, timeOfDayBpFor("2026-11-11T11:30:00Z"), "17:00 local");
        assertEquals(500, timeOfDayBpFor("2026-11-11T17:29:00Z"), "22:59 local");
        assertEquals(0,   timeOfDayBpFor("2026-11-11T17:30:00Z"), "23:00 local");
    }

    // ---------------------------------------------------------- day of week

    private int dayOfWeekBpFor(String startsAt) {
        Instant start = Instant.parse(startsAt);
        return bpOf(rules().priceFor(BASE, 0, SEATS, start, daysBefore(start, 3)),
                PricingRules.DAY_OF_WEEK);
    }

    /**
     * The sharpest version of the same trap. All three of these are 00:30
     * local, so the time-of-day rule stays quiet, and the first and last give
     * the opposite answer under UTC from under Asia/Kolkata.
     */
    @Test
    @DisplayName("weekend is read in the configured zone, not UTC")
    void dayOfWeekBands() {
        assertEquals(500, dayOfWeekBpFor("2026-11-12T19:00:00Z"),
                "Thursday in UTC, Friday 00:30 in Kolkata");
        assertEquals(500, dayOfWeekBpFor("2026-11-13T19:00:00Z"),
                "Saturday 00:30 in Kolkata");
        assertEquals(0,   dayOfWeekBpFor("2026-11-14T19:00:00Z"),
                "Saturday in UTC, Sunday 00:30 in Kolkata");
    }

    // ----------------------------------------------------------- everything

    /**
     * The headline case, and the row from the price table that was agreed:
     * a 200 rupee seat becomes 280 at full tilt. Pinned as a test so tuning a
     * band cannot quietly change the number in the README.
     */
    @Test
    @DisplayName("full house, Friday evening, three hours out: 200 becomes 280")
    void allFourRulesTogether() {
        // Friday 13 November 2026, 19:00 Asia/Kolkata
        Instant start = Instant.parse("2026-11-13T13:30:00Z");
        PriceBreakdown b = rules().priceFor(
                BASE, SEATS, SEATS, start, start.minus(Duration.ofHours(3)));

        assertEquals(2000, bpOf(b, PricingRules.OCCUPANCY));
        assertEquals(1000, bpOf(b, PricingRules.LEAD_TIME));
        assertEquals(500,  bpOf(b, PricingRules.TIME_OF_DAY));
        assertEquals(500,  bpOf(b, PricingRules.DAY_OF_WEEK));

        assertEquals(28_000, b.adjustedPriceMinor());
        assertEquals(4, b.rules().size(), "the cap must not fire at exactly the ceiling");
        assertAddsUp(b);
    }

    @Test
    @DisplayName("the 550 rupee executive seat becomes 770 in the same conditions")
    void allFourRulesOnADearerSeat() {
        Instant start = Instant.parse("2026-11-13T13:30:00Z");
        PriceBreakdown b = rules().priceFor(
                55_000, SEATS, SEATS, start, start.minus(Duration.ofHours(3)));

        assertEquals(77_000, b.adjustedPriceMinor());
        assertAddsUp(b);
    }

    // ------------------------------------------------------------------ cap

    /**
     * The shipped config cannot reach the cap, because the four bands sum to
     * exactly the ceiling. So this lowers the ceiling to prove the clamp
     * works rather than shipping it hot.
     *
     * The cap is a line on the receipt, not a silent truncation. That is what
     * keeps assertAddsUp true: if the clamp just shrank the total, the four
     * lines would sum to more than the price charged.
     */
    @Test
    @DisplayName("over the ceiling, the cap appears as its own negative line")
    void capIsALineNotATruncation() {
        Instant start = Instant.parse("2026-11-13T13:30:00Z");
        PriceBreakdown b = rules(3000).priceFor(
                BASE, SEATS, SEATS, start, start.minus(Duration.ofHours(3)));

        assertEquals(5, b.rules().size(), "four rules plus the cap");
        assertEquals(-1000, bpOf(b, PricingRules.CAP),
                "the four rules wanted 4000, the ceiling is 3000");
        assertEquals(26_000, b.adjustedPriceMinor(), "capped at 30 percent of 200");
        assertAddsUp(b);
    }

    // --------------------------------------------------------- the rounding

    @Test
    @DisplayName("applyBp rounds half away from zero, in integers")
    void applyBpRounds() {
        assertEquals(4000, PricingRules.applyBp(20_000, 2000));
        assertEquals(2750, PricingRules.applyBp(55_000, 500));
        assertEquals(0,    PricingRules.applyBp(20_000, 0));
        assertEquals(-2000, PricingRules.applyBp(20_000, -1000));

        /* Exactly a half rounds away from zero in both directions, which is
           the only rounding rule that is symmetric about zero. Half-up would
           drift discounts and surcharges in opposite directions. */
        assertEquals(1,  PricingRules.applyBp(1, 5000));
        assertEquals(-1, PricingRules.applyBp(1, -5000));
    }

    @Test
    @DisplayName("a large price times a large band does not overflow")
    void noOverflow() {
        /* 10 million paise is 100,000 rupees, far above any real seat, and
           times 4000 basis points it overflows a 32-bit int. If the product
           inside applyBp were an int this would come back negative. */
        assertEquals(4_000_000, PricingRules.applyBp(10_000_000, 4000));
    }
}