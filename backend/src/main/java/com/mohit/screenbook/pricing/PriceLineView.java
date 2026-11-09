package com.mohit.screenbook.pricing;

/**
 * One line of a price breakdown as the API reports it. Field names are the
 * JSON keys, so this file and docs/api-contract.md have to agree.
 *
 * basisPoints is an integer, never a float, for the same reason every amount
 * in this API is: 2000 is 20 percent, and the client divides by 100 to display
 * it. adjustmentMinor is what that percentage came to in money, worked out
 * server side so the client never does price arithmetic.
 *
 * Both are signed. A discount band and the cap line are negative.
 */
public record PriceLineView(
        String code,
        String label,
        int basisPoints,
        int adjustmentMinor
) {
}