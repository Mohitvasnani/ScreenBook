package com.mohit.screenbook.catalogue;

/**
 * One row of the seat_availability view. Internal: this is what the query
 * returns, not what the API sends. SeatMapSeat is the JSON shape.
 *
 * The two were the same record until pricing became dynamic. A row carries the
 * listed price, because that is what the database stores; a response carries
 * the price after the rules, because that is what a customer pays. Keeping one
 * type for both would mean a field that is only true after something else has
 * fixed it up.
 *
 * tier is a String, not an enum, on purpose. The tiers live in the seat_tier
 * table so that adding one is a single row; a Java enum would make it a row
 * plus a code change and give the lookup table nothing to do.
 */
public record SeatView(
        long id,
        String rowLabel,
        int seatNumber,
        SeatStatus status,
        String tier,
        String tierName,
        int basePriceMinor,
        String currency
) {
}