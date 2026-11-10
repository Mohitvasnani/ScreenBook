package com.mohit.screenbook.catalogue;

import com.mohit.screenbook.common.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The read side. Plain SQL rather than JPA, because these responses are joins
 * across three tables plus counts and match no entity. Writing the join is
 * clearer than persuading Hibernate to produce it.
 *
 * Both queries return rows, not responses. Pricing is applied above this, in
 * ShowtimeListService and SeatMapService, because what a seat costs depends on
 * the clock and on how full the house is and neither belongs in a query.
 */
@Repository
public class ShowtimeQuery {

    /* base_from_price_minor is the cheapest listed seat for this screening: the
       lowest row in the price table for its hall class. ShowtimeListService
       applies the rules to it, because the contract calls this a "from" price
       and a headline price nobody can buy at is exactly what the CMA's pricing
       guidance is about.

       That subquery must filter on currency. Without it, min() runs across
       every currency in the table and a Jaipur screening advertises from 900,
       which is nine pounds read as nine rupees. The price table has been
       keyed on currency since V17 and every join to it has to say so; this is
       the third place in the wave where forgetting that has caused a bug.

       currency comes from the cinema rather than from showtime.currency. The
       cinema is where it belongs, because a building does not change
       currency, and keeping a copy on the screening would be a second source
       of truth that can disagree with the first.

       seats_taken comes from showtime_occupancy rather than being counted here.
       The old version subtracted count(*) from booking and seat_hold, which
       counted bookings and holds rather than seats, so it over-reported
       availability for every multi-seat booking after V8. Occupancy now has one
       definition and the pricing rules read the same view. */
    private static final String UPCOMING = """
            SELECT st.id              AS id,
                   f.title            AS film_title,
                   sc.name            AS screen_name,
                   st.starts_at       AS starts_at,
                   f.runtime_minutes  AS runtime_minutes,
                   ci.currency        AS currency,

                   (SELECT min(p.price_minor)
                      FROM hall_class_tier_price p
                     WHERE p.currency   = ci.currency
                       AND p.hall_class = sc.hall_class)   AS base_from_price_minor,

                   occ.seats_taken                         AS seats_taken,
                   occ.seats_total                         AS seats_total

            FROM showtime st
            JOIN film   f   ON f.id  = st.film_id
            JOIN screen sc  ON sc.id = st.screen_id
            JOIN cinema ci  ON ci.id = sc.cinema_id
            JOIN showtime_occupancy occ ON occ.showtime_id = st.id
            WHERE st.starts_at > now()
            ORDER BY st.starts_at
            LIMIT :limit
            """;

    /* Everything the pricing rules need, in one read. seats_taken deliberately
       does not include the seats being claimed right now: the caller has locked
       those rows but not yet written a hold, so the view still reports them
       AVAILABLE. Your own request never inflates your own price. */
    private static final String PRICING_INPUTS = """
            SELECT occ.seats_taken  AS seats_taken,
                   occ.seats_total  AS seats_total,
                   st.starts_at     AS starts_at
            FROM showtime st
            JOIN showtime_occupancy occ ON occ.showtime_id = st.id
            WHERE st.id = :showtimeId
            """;

    private final JdbcClient jdbc;

    ShowtimeQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<ShowtimeRow> upcomingRows(int limit) {
        return jdbc.sql(UPCOMING)
                .param("limit", limit)
                .query((rs, rowNum) -> new ShowtimeRow(
                        rs.getLong("id"),
                        rs.getString("film_title"),
                        rs.getString("screen_name"),
                        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("runtime_minutes"),
                        rs.getInt("base_from_price_minor"),
                        rs.getString("currency"),
                        rs.getInt("seats_taken"),
                        rs.getInt("seats_total")))
                .list();
    }

    /**
     * No @Transactional, deliberately, and for the same reason as
     * SeatMapQuery.seatsByIds: this runs inside the claim transaction, after
     * it has locked the seat rows, and must read what that transaction can
     * see. Opening its own transaction here would read a different snapshot
     * and the price could be computed from an occupancy nobody ever had.
     */
    public ShowtimePricingInputs pricingInputsFor(long showtimeId) {
        return jdbc.sql(PRICING_INPUTS)
                .param("showtimeId", showtimeId)
                .query((rs, rowNum) -> new ShowtimePricingInputs(
                        rs.getInt("seats_taken"),
                        rs.getInt("seats_total"),
                        rs.getObject("starts_at", OffsetDateTime.class).toInstant()))
                .optional()
                .orElseThrow(() -> new NotFoundException(
                        "No showtime with id %d.".formatted(showtimeId)));
    }
}