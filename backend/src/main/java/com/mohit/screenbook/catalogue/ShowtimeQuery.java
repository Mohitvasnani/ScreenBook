package com.mohit.screenbook.catalogue;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.mohit.screenbook.common.NotFoundException;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The read side. Plain SQL rather than JPA, because this response is a join
 * across three tables plus two counts and matches no entity. Writing the join
 * is clearer than persuading Hibernate to produce it.
 */
@Repository
public class ShowtimeQuery {

    /* price_minor is the cheapest seat in the house for this screening, which
       is the lowest row in the price table for this screen's hall class. The
       contract calls it a "from" price, so it has to be a price somebody can
       actually buy at.

       seats_available comes from showtime_occupancy rather than being counted
       here. The old version subtracted count(*) from booking and seat_hold,
       which counted bookings and holds rather than seats, so it over-reported
       availability for every multi-seat booking after V8. Occupancy now has
       one definition, and the pricing rules read the same view. */
    private static final String UPCOMING = """
            SELECT st.id              AS id,
                   f.title            AS film_title,
                   sc.name            AS screen_name,
                   st.starts_at       AS starts_at,
                   f.runtime_minutes  AS runtime_minutes,
                   st.currency        AS currency,

                   (SELECT min(p.price_minor)
                      FROM hall_class_tier_price p
                     WHERE p.hall_class = sc.hall_class)   AS price_minor,

                   occ.seats_total                         AS seats_total,
                   occ.seats_total - occ.seats_taken       AS seats_available

            FROM showtime st
            JOIN film   f   ON f.id  = st.film_id
            JOIN screen sc  ON sc.id = st.screen_id
            JOIN showtime_occupancy occ ON occ.showtime_id = st.id
            WHERE st.starts_at > now()
            ORDER BY st.starts_at
            LIMIT :limit
            """;

    private final JdbcClient jdbc;

    ShowtimeQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<ShowtimeSummary> upcoming(int limit) {
        return jdbc.sql(UPCOMING)
                .param("limit", limit)
                .query((rs, rowNum) -> new ShowtimeSummary(
                        rs.getLong("id"),
                        rs.getString("film_title"),
                        rs.getString("screen_name"),
                        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("runtime_minutes"),
                        rs.getInt("price_minor"),
                        rs.getString("currency"),
                        rs.getInt("seats_available"),
                        rs.getInt("seats_total")))
                .list();
    }
    /* Everything the pricing rules need, in one read. seats_taken deliberately
   does not include the seats being claimed right now: the caller has
   locked those rows but not yet written a hold, so the view still reports
   them AVAILABLE. Your own request never inflates your own price. */
    private static final String PRICING_INPUTS = """
            SELECT occ.seats_taken  AS seats_taken,
                   occ.seats_total  AS seats_total,
                   st.starts_at     AS starts_at
            FROM showtime st
            JOIN showtime_occupancy occ ON occ.showtime_id = st.id
            WHERE st.id = :showtimeId
            """;
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