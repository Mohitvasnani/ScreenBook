package com.mohit.screenbook.catalogue;

import com.mohit.screenbook.pricing.ScreeningFacts;

/**
 * One screening in the from-price window, for one cinema. Internal: the number
 * this produces reaches the wire as NearestCinema.fromPriceMinor.
 *
 * Holds ScreeningFacts itself rather than restating its five fields and
 * mapping them, which is what ShowtimeRow and ShowtimePricingInputs both do.
 * They are flat because their fields also go on the wire or are read inside a
 * locked transaction. These rows feed the pricing rules and nothing else, so
 * there is no second shape to agree with and a fourth copy of the same five
 * fields would be four places to add the sixth rule's input.
 *
 * baseFromPriceMinor is the cheapest listed seat for this screening, the same
 * column SELECT_SHOWTIME computes and for the same reason: all five pricing
 * rules are facts about the screening rather than about the seat, so the
 * cheapest seat on sale is always in the cheapest tier.
 *
 * No currency. The price is in the cinema's own currency by construction,
 * because the price subquery filters on ci.currency, and the cinema row this
 * number is attached to already carries it.
 */
public record FromPriceRow(long cinemaId,
                           int baseFromPriceMinor,
                           ScreeningFacts facts) {
}