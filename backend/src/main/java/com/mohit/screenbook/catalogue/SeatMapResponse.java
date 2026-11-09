package com.mohit.screenbook.catalogue;

import com.mohit.screenbook.pricing.PriceRateView;

import java.util.List;

/**
 * priceRules lists only the rules currently moving prices, as percentages.
 * Empty means every seat is at its listed price, which a client should render
 * as a single line rather than an empty panel.
 */
public record SeatMapResponse(
        long showtimeId,
        List<SeatMapSeat> seats,
        List<PriceRateView> priceRules
) {
}