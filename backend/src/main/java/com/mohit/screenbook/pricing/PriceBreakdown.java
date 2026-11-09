package com.mohit.screenbook.pricing;

import java.util.List;

/**
 * What a seat costs and why.
 *
 * rules always holds one line per rule, including the ones that landed in a
 * zero band, because storing all of them is what keeps "this rule did not
 * fire" distinguishable from "this rule did not exist yet". The API filters
 * the zeros out; the database keeps them.
 *
 * The invariant worth a test of its own: basePriceMinor plus the sum of every
 * line's adjustmentMinor equals adjustedPriceMinor, always, cap included.
 */
public record PriceBreakdown(
        int basePriceMinor,
        int adjustedPriceMinor,
        List<PriceRule> rules
) {
}