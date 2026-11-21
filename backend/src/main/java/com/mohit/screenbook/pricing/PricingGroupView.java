package com.mohit.screenbook.pricing;

import java.util.List;

/**
 * Why the seats of one tier on a hold cost what they cost, as the API reports
 * it.
 *
 * A hold can span tiers, and the rules are percentages of a base, so each tier
 * has its own breakdown: the same percentages, a different listed price and
 * therefore different amounts. baseMinor is that tier's listed price,
 * adjustedMinor is what one seat of that tier costs after the rules, and
 * seatCount is how many seats on the hold are in it.
 *
 * baseMinor plus every adjustmentMinor in rules equals adjustedMinor, always,
 * including the CAP line if it ever appears. The invariant holds per group, so
 * every line on a receipt is true on its own.
 *
 * rules holds only the lines that moved the price. An empty list is the
 * ordinary case and means the listed price stands.
 */
public record PricingGroupView(
        String tier,
        int seatCount,
        int baseMinor,
        int adjustedMinor,
        List<PriceLineView> rules
) {
}