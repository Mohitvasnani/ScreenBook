package com.mohit.screenbook.catalogue;

import com.mohit.screenbook.common.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * The read side. Plain SQL rather than JPA, because these responses are joins
 * across four tables plus counts and match no entity. Writing the join is
 * clearer than persuading Hibernate to produce it.
 *
 * These return rows, not responses. Pricing is applied above this, in
 * ShowtimeListService and SeatMapService, because what a seat costs depends on
 * the clock and on how full the house is and neither belongs in a query.
 */
@Repository
public class ShowtimeQuery {

    /* Shared by the listing and the single read, which have to agree about the
       shape of a screening down to the column order, because one row mapper
       reads both. Two copies of this select list would be two places to add the
       next column and one place to forget it.

       base_from_price_minor is the cheapest listed seat for this screening: the
       lowest row in the price table for its hall class. ShowtimeListService
       applies the rules to it, because the contract calls this a "from" price
       and a headline price nobody can buy at is exactly what the CMA's pricing
       guidance is about.

       That subquery must filter on currency. Without it, min() runs across
       every currency in the table and a Jaipur screening advertises from 900,
       which is nine pounds read as nine rupees. The price table has been keyed
       on currency since V17 and every join to it has to say so.

       currency and cinema_id come from the cinema rather than from the
       screening. The cinema is where they belong: a building does not change
       currency, and a copy on the screening would be a second source of truth
       that can disagree with the first.

       seats_taken comes from showtime_occupancy rather than being counted here.
       The old version subtracted count(*) from booking and seat_hold, which
       counted bookings and holds rather than seats, so it over-reported
       availability for every multi-seat booking after V8. Occupancy now has one
       definition and the pricing rules read the same view. */
    private static final String SELECT_SHOWTIME = """
            SELECT st.id              AS id,
                   ci.id              AS cinema_id,
                   f.title            AS film_title,
                   sc.name            AS screen_name,
                   st.starts_at       AS starts_at,
                   f.runtime_minutes  AS runtime_minutes,
                   ci.currency        AS currency,
                   ci.timezone        AS timezone,

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
            """;

    /* The filter is sc.cinema_id, not ci.id, even though the join makes them
       equal. Postgres does not index a foreign key automatically, but
       screen_unique_in_cinema from V16 is a btree on (cinema_id, name), so
       cinema_id is its leading column and "the screens at this cinema" is an
       index lookup. From there showtime_unique_slot on (screen_id, starts_at)
       is already the index this ordering wants. */
    private static final String UPCOMING = SELECT_SHOWTIME + """
            WHERE sc.cinema_id  = :cinemaId
              AND st.starts_at  > now()
            ORDER BY st.starts_at
            LIMIT :limit
            """;

    /* No starts_at filter here, unlike the listing, and that is the difference
       between the two. A listing shows what you can still book. A single read
       answers "what is this screening", and a confirmation page or a bookmarked
       seat map opened after the film started still has to render rather than
       404. Whether it can be booked is the hold endpoint's answer, which
       already rejects a started showtime with a 409. */
    private static final String ONE = SELECT_SHOWTIME + """
            WHERE st.id = :showtimeId
            """;

    /* Everything the pricing rules need, in one read. seats_taken deliberately
       does not include the seats being claimed right now: the caller has locked
       those rows but not yet written a hold, so the view still reports them
       AVAILABLE. Your own request never inflates your own price. */
    private static final String PRICING_INPUTS = """
            SELECT occ.seats_taken  AS seats_taken,
                   occ.seats_total  AS seats_total,
                   st.starts_at     AS starts_at,
                   ci.timezone      AS timezone
            FROM showtime st
            JOIN showtime_occupancy occ ON occ.showtime_id = st.id
            JOIN screen sc  ON sc.id = st.screen_id
            JOIN cinema ci  ON ci.id = sc.cinema_id
            WHERE st.id = :showtimeId
            """;

    private static final String CINEMA_EXISTS =
            "SELECT exists(SELECT 1 FROM cinema WHERE id = :cinemaId)";

    private final JdbcClient jdbc;

    ShowtimeQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<ShowtimeRow> upcomingRows(long cinemaId, int limit) {
        List<ShowtimeRow> rows = jdbc.sql(UPCOMING)
                .param("cinemaId", cinemaId)
                .param("limit", limit)
                .query(ShowtimeQuery::toRow)
                .list();

        /* Same shape as SeatMapQuery.rowsFor. An empty list is ambiguous: a
           cinema that does not exist, or a real cinema the seeder has not
           reached. They are different answers and only the second is an empty
           list, so the second query is worth paying for, and only then. */
        if (rows.isEmpty() && !cinemaExists(cinemaId)) {
            throw new NotFoundException("No cinema with id %d.".formatted(cinemaId));
        }
        return rows;
    }

    /**
     * One screening by its own id.
     *
     * No ambiguity to resolve here, so no second query: a single read either
     * found the row or there is no such screening.
     */
    @Transactional(readOnly = true)
    public ShowtimeRow oneRow(long showtimeId) {
        return jdbc.sql(ONE)
                .param("showtimeId", showtimeId)
                .query(ShowtimeQuery::toRow)
                .optional()
                .orElseThrow(() -> new NotFoundException(
                        "No showtime with id %d.".formatted(showtimeId)));
    }

    private boolean cinemaExists(long cinemaId) {
        return jdbc.sql(CINEMA_EXISTS)
                .param("cinemaId", cinemaId)
                .query(Boolean.class)
                .single();
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
                        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                        ZoneId.of(rs.getString("timezone"))))
                .optional()
                .orElseThrow(() -> new NotFoundException(
                        "No showtime with id %d.".formatted(showtimeId)));
    }

    private static ShowtimeRow toRow(ResultSet rs, int rowNum) throws SQLException {
        return new ShowtimeRow(
                rs.getLong("id"),
                rs.getLong("cinema_id"),
                rs.getString("film_title"),
                rs.getString("screen_name"),
                rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                ZoneId.of(rs.getString("timezone")),
                rs.getInt("runtime_minutes"),
                rs.getInt("base_from_price_minor"),
                rs.getString("currency"),
                rs.getInt("seats_taken"),
                rs.getInt("seats_total"));
    }
}