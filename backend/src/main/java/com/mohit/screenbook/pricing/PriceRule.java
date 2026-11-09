package com.mohit.screenbook.pricing;

/**
 * One line of the breakdown: which rule, which band it landed in, and what
 * that cost. Both numbers are signed, because a discount and the cap line are
 * negative.
 *
 * label is resolved copy rather than a code, because it is a snapshot. The
 * text depends on the band, not just the rule, and it is the explanation that
 * was on the customer's screen. Rewording a band later must not change why an
 * old booking cost what it did.
 */
public record PriceRule(
        String code,
        String label,
        int basisPoints,
        int adjustmentMinor,
        int sortOrder
) {
}