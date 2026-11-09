package com.mohit.screenbook.catalogue;

/**
 * One seat in GET /api/showtimes/{id}/seats. Field names are the JSON keys, so
 * this file and docs/api-contract.md have to agree.
 *
 * basePriceMinor is the listed price for this hall class and tier.
 * priceMinor is what it costs right now, after the rules. They are equal when
 * no rule is firing, and both are sent so the client can show the difference
 * without doing the arithmetic itself.
 *
 * Neither is frozen. These move as the house fills and the screening
 * approaches. A price is only promised once a hold exists.
 */
public record SeatMapSeat(
        long id,
        String rowLabel,
        int seatNumber,
        SeatStatus status,
        String tier,
        String tierName,
        int basePriceMinor,
        int priceMinor,
        String currency
) {
}