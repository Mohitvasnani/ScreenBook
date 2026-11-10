package com.mohit.screenbook.catalogue;

import com.mohit.screenbook.pricing.PricingRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Showtimes, priced.
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
    public List<ShowtimeSummary> upcoming(long cinemaId, int limit) {
        Instant now = clock.instant();

        return showtimes.upcomingRows(cinemaId, limit).stream()
                .map(row -> priced(row, now))
                .toList();
    }

    /**
     * One screening, priced the same way a row in the list is.
     *
     * The prices are live, not frozen, exactly as in the list: this is what the
     * screening costs right now. The promise only begins when a hold exists.
     */
    @Transactional(readOnly = true)
    public ShowtimeSummary one(long showtimeId) {
        return priced(showtimes.oneRow(showtimeId), clock.instant());
    }

    /* Shared so a screening read on its own and the same screening read in a
       list cannot be priced by two different pieces of code. */
    private ShowtimeSummary priced(ShowtimeRow row, Instant now) {
        return new ShowtimeSummary(
                row.id(),
                row.cinemaId(),
                row.filmTitle(),
                row.screenName(),
                row.startsAt(),
                row.runtimeMinutes(),
                row.baseFromPriceMinor(),
                pricing.priceFor(
                        row.baseFromPriceMinor(),
                        row.seatsTaken(), row.seatsTotal(),
                        row.startsAt(), row.zone(), now).adjustedPriceMinor(),
                row.currency(),
                row.seatsTotal() - row.seatsTaken(),
                row.seatsTotal());
    }
}