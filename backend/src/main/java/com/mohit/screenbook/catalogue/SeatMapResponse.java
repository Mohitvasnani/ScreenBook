package com.mohit.screenbook.catalogue;

import com.mohit.screenbook.pricing.PriceRateView;

import java.util.List;

/**
 * priceRules lists only the rules currently moving prices, as percentages.
 * Empty means every seat is at its listed price, which a client should render
 * as a single line rather than an empty panel.
 *
 * aisleAfter is the seat numbers after which the room has a gangway, straight
 * from screen.aisle_after. The positions, not the blocks they imply: blocks
 * are a rendering, and the client derives them from these plus the row width
 * it can already read off the seats. Sending blocks from here would put a
 * layout decision in the API and triple the payload to say the same thing.
 *
 * An empty list is a room with no aisle at all. That is a legitimate small
 * screen, not a missing value, so a client must render it rather than treat
 * it as an error.
 */
public record SeatMapResponse(
        long showtimeId,
        List<SeatMapSeat> seats,
        List<Integer> aisleAfter,
        List<PriceRateView> priceRules
) {
}