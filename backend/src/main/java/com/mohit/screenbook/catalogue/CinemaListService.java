package com.mohit.screenbook.catalogue;

import com.mohit.screenbook.pricing.PricingRules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Cinemas, with the cheapest price on sale at each.
 *
 * Two queries, not one join. The geography query has already chosen the
 * cinemas and applied the clamp, so the price query takes its result and never
 * states a radius of its own. One place knows what use_spheroid is set to.
 *
 * One clock reading for the whole response, so the window and the lead-time
 * rule are decided by the same instant. Two readings would let a screening
 * fall inside the window and then be priced as though it were further away
 * than the window allows, which is a contradiction nobody would ever see and
 * nobody could ever reproduce.
 *
 * readOnly transactional across both queries on purpose, so the cinemas and
 * their prices come from one snapshot. Without it, a cinema whose only
 * screening in the window starts between the two queries would be listed with
 * no price, having had one a millisecond earlier.
 */
@Service
public class CinemaListService {

    private final CinemaQuery cinemas;
    private final ShowtimeQuery showtimes;
    private final PricingRules pricing;
    private final Clock clock;
    private final Duration window;

    CinemaListService(CinemaQuery cinemas,
                      ShowtimeQuery showtimes,
                      PricingRules pricing,
                      Clock clock,
                      @Value("${screenbook.cinemas.from-price-window-hours}") int windowHours) {
        this.cinemas = cinemas;
        this.showtimes = showtimes;
        this.pricing = pricing;
        this.clock = clock;
        this.window = Duration.ofHours(windowHours);
    }

    @Transactional(readOnly = true)
    public List<NearestCinema> nearest(double lat, double lon, int limit) {
        return priced(cinemas.nearest(lat, lon, limit));
    }

    /**
     * The radius, and the one cinema outside it when the radius is empty.
     *
     * The fallback query runs only in the case that found nothing, which is
     * the case with nothing to render, so its latency is free. Folding it into
     * one statement with a window function would pay for it on every call to
     * save it on the rare one.
     *
     * The fallback is priced too. "Nothing within 10km, the closest is 34km
     * away and from 850" is the sentence this endpoint exists to let the
     * client say, and the price is half of it.
     */
    @Transactional(readOnly = true)
    public CinemasWithin within(double lat, double lon, int radiusMetres) {
        List<NearestCinema> inside = cinemas.within(lat, lon, radiusMetres);

        if (!inside.isEmpty()) {
            return new CinemasWithin(radiusMetres, priced(inside), null);
        }

        List<NearestCinema> fallback = priced(cinemas.nearest(lat, lon, 1));
        return new CinemasWithin(radiusMetres, List.of(),
                fallback.isEmpty() ? null : fallback.getFirst());
    }

    /* A cinema with no screening in the window keeps its null, because
       cheapest.get returns null for a key the map has no entry for. That is
       the one case the map is deliberately not total for: absence of a price
       and a price of zero are different answers, and the only honest way to
       say the first is to say nothing. */
    private List<NearestCinema> priced(List<NearestCinema> rows) {
        if (rows.isEmpty()) {
            return rows;
        }

        Instant now = clock.instant();
        Map<Long, Integer> cheapest =
                cheapestByCinema(rows.stream().map(NearestCinema::id).toList(), now);

        return rows.stream()
                .map(cinema -> cinema.withFromPrice(cheapest.get(cinema.id())))
                .toList();
    }

    /**
     * One priced screening becomes one map entry, and Math::min settles the
     * collisions. That merge function is the whole aggregate: the query
     * returns a row per screening and this is where it becomes a row per
     * cinema.
     *
     * It is also the part that has to be here rather than in SQL. min() over
     * base_from_price_minor is answerable in one line of SQL and is the wrong
     * number, because the five rules key off occupancy, lead time, time of
     * day, day of week and weeks since release, and the base price is what a
     * seat costs before any of them has applied.
     *
     * Without the merge function this throws IllegalStateException on the
     * second screening at any cinema, which is every cinema. Worth knowing
     * that the failure mode is loud.
     */
    private Map<Long, Integer> cheapestByCinema(List<Long> cinemaIds, Instant now) {
        return showtimes.cheapestInWindow(cinemaIds, now, now.plus(window)).stream()
                .collect(Collectors.toMap(
                        FromPriceRow::cinemaId,
                        row -> pricing.priceFor(row.baseFromPriceMinor(), row.facts(), now)
                                .adjustedPriceMinor(),
                        Math::min));
    }
}