package com.mohit.screenbook.pricing;

/**
 * A rule and its percentage, with no money attached.
 *
 * The seat map spans all three tiers at once, and the rules are percentages of
 * each tier's own listed price, so one shared money amount would be wrong for
 * two of the three. The percentages are identical across tiers, so the map
 * reports those and leaves every seat to carry its own price. The checkout
 * screen, where a hold is always one tier, gets the money version in
 * PricingView instead.
 */
public record PriceRateView(String code, String label, int basisPoints) {
}