package com.mohit.screenbook.catalogue;

import com.mohit.screenbook.pricing.PricingRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * The showtimes list, priced.
 *
 * One pricing call per screening rather than per seat, because the "from"
 * price is the cheapest tier and the rules are a fact about the screening. The
 * query already returns occupancy and the start time, so this costs no extra
 * reads, and priceFor is a pure function.
 *
 * One clock reading for the whole list, so twelve rows cannot be priced
 * against twelve slightly different instants and a row cannot cross a
 * lead-time boundary halfway down the page.
 */
@Service
public class ShowtimeListService {

    private final ShowtimeQuery showtimes;
    private final PricingRules pricing;
    private final Clock clock;

    ShowtimeListService(ShowtimeQuery showtimes, PricingRules pricing, Clock clock) {
        this.showtimes = showtimes;
        this.pricing = pricing;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ShowtimeSummary> upcoming(int limit) {
        Instant now = clock.instant();

        return showtimes.upcomingRows(limit).stream()
                .map(row -> new ShowtimeSummary(
                        row.id(),
                        row.filmTitle(),
                        row.screenName(),
                        row.startsAt(),
                        row.runtimeMinutes(),
                        row.baseFromPriceMinor(),
                        pricing.priceFor(
                                row.baseFromPriceMinor(),
                                row.seatsTaken(), row.seatsTotal(),
                                row.startsAt(), now).adjustedPriceMinor(),
                        row.currency(),
                        row.seatsTotal() - row.seatsTaken(),
                        row.seatsTotal()))
                .toList();
    }
}