package com.mohit.screenbook.catalogue;

import com.mohit.screenbook.pricing.PriceBreakdown;
import com.mohit.screenbook.pricing.PriceRateView;
import com.mohit.screenbook.pricing.PricingRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The seat map, priced.
 *
 * This exists because the seat map has to show the same number the checkout
 * will quote. Leave the map on the listed price and a customer picks a seat
 * at 200 and is asked for 280, which is the kind of surprise the CMA's
 * guidance on headline prices is about.
 *
 * The prices here are live, not frozen. They move as the house fills. The
 * promise only begins when a hold exists, which is the honest version of
 * "this price is held for you".
 */
@Service
public class SeatMapService {

    private final SeatMapQuery seats;
    private final ShowtimeQuery showtimes;
    private final PricingRules pricing;
    private final Clock clock;

    SeatMapService(SeatMapQuery seats, ShowtimeQuery showtimes,
                   PricingRules pricing, Clock clock) {
        this.seats = seats;
        this.showtimes = showtimes;
        this.pricing = pricing;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SeatMapResponse forShowtime(long showtimeId) {
        List<SeatView> rows = seats.rowsFor(showtimeId);

        /* After rowsFor, which has already answered "does this screening
           exist". Three reads on this path now, all inside one read-only
           transaction, so they see one snapshot of the room. */
        List<Integer> aisleAfter = seats.layoutFor(showtimeId);
        ShowtimePricingInputs inputs = showtimes.pricingInputsFor(showtimeId);
        Instant now = clock.instant();

        /* One breakdown per distinct listed price, so at most three for a
           house of 120 seats rather than 120. The rules are facts about the
           screening, so the only thing that differs between tiers is the base
           they scale. */
        Map<Integer, PriceBreakdown> byBase = rows.stream()
                .map(SeatView::basePriceMinor)
                .distinct()
                .collect(Collectors.toMap(
                        base -> base,
                        base -> pricing.priceFor(base,
                                inputs.seatsTaken(), inputs.seatsTotal(),
                                inputs.startsAt(), inputs.zone(), now)));

        List<SeatMapSeat> priced = rows.stream()
                .map(row -> new SeatMapSeat(
                        row.id(), row.rowLabel(), row.seatNumber(), row.status(),
                        row.tier(), row.tierName(),
                        row.basePriceMinor(),
                        byBase.get(row.basePriceMinor()).adjustedPriceMinor(),
                        row.currency()))
                .toList();

        return new SeatMapResponse(showtimeId, priced, aisleAfter, rates(byBase));
    }

    /**
     * The percentages are the same whatever the base, so any one breakdown
     * answers for all of them. The money amounts are not, which is why this
     * drops them and every seat carries its own price instead.
     *
     * Zero bands are filtered out here, as they are on the hold: they stay in
     * the database so that a rule which did not fire is distinguishable from
     * one that did not exist, and a customer does not need to read them.
     */
    private static List<PriceRateView> rates(Map<Integer, PriceBreakdown> byBase) {
        return byBase.values().stream()
                .findFirst()
                .map(breakdown -> breakdown.rules().stream()
                        .filter(rule -> rule.basisPoints() != 0)
                        .map(rule -> new PriceRateView(
                                rule.code(), rule.label(), rule.basisPoints()))
                        .toList())
                .orElse(List.of());
    }
}