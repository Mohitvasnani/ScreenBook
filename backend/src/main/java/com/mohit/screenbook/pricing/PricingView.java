package com.mohit.screenbook.pricing;

import java.util.List;

/**
 * Why a seat costs what it costs, as the API reports it.
 *
 * baseMinor is the listed price from the hall class and tier table.
 * adjustedMinor is what one seat actually costs after the rules. rules holds
 * only the lines that moved the price: the bands that did not fire are kept in
 * the database so that "did not fire" stays distinguishable from "did not
 * exist yet", but a line reading "Occupancy, plus nothing" is noise on a
 * receipt.
 *
 * An empty rules list is the ordinary case and means the listed price stands.
 * A client should render that as one line rather than an empty panel.
 */
public record PricingView(
        int baseMinor,
        int adjustedMinor,
        List<PriceLineView> rules
) {
}