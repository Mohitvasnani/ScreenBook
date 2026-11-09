package com.mohit.screenbook.catalogue;

import com.mohit.screenbook.common.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Repository
public class SeatMapQuery {

    /* base_price_minor since V12: the seat's listed price, before any demand
       rule touches it. It lands in SeatView.priceMinor, which is still the
       final price as far as the API is concerned, because no rules exist yet.
       When they do, the rules are applied between this query and the response,
       and that is the step that makes the two names mean different things. */
    private static final String SEAT_MAP = """
            SELECT seat_id, row_label, seat_number, status,
                   tier, tier_name, base_price_minor, currency
            FROM seat_availability
            WHERE showtime_id = :showtimeId
            ORDER BY row_label, seat_number
            """;

    private static final String SEATS_BY_ID = """
            SELECT seat_id, row_label, seat_number, status,
                   tier, tier_name, base_price_minor, currency
            FROM seat_availability
            WHERE showtime_id = :showtimeId
              AND seat_id IN (:seatIds)
            ORDER BY row_label, seat_number
            """;

    private static final String SHOWTIME_EXISTS =
            "SELECT exists(SELECT 1 FROM showtime WHERE id = :showtimeId)";

    private final JdbcClient jdbc;

    SeatMapQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<SeatView> rowsFor(long showtimeId) {
        List<SeatView> seats = jdbc.sql(SEAT_MAP)
                .param("showtimeId", showtimeId)
                .query(SeatMapQuery::toSeatView)
                .list();

        /* An empty list is ambiguous: either no such showtime, or a real
           showtime whose screen has no seats. Only then is a second query
           worth paying for. */
        if (seats.isEmpty() && !showtimeExists(showtimeId)) {
            throw new NotFoundException("No showtime with id %d.".formatted(showtimeId));
        }
        return seats;
    }

    /**
     * Several seats at once, with their status and listed price. One query
     * rather than one per seat, and ordered for display, so the claim path
     * gets its availability check and its response data from the same read.
     *
     * No @Transactional, deliberately: this runs inside the claim transaction,
     * after it has locked the seat rows, and must read what that transaction
     * can see. Opening its own transaction would read outside the lock and the
     * lock would protect nothing.
     *
     * Fewer rows than seat ids means a seat that is not in this showtime's
     * screen. The caller decides what that is: in HoldService it is a 404,
     * because a seat that was never there is a different mistake from a seat
     * that has gone.
     */
    public List<SeatView> seatsByIds(long showtimeId, Collection<Long> seatIds) {
        return jdbc.sql(SEATS_BY_ID)
                .param("showtimeId", showtimeId)
                .param("seatIds", seatIds)
                .query(SeatMapQuery::toSeatView)
                .list();
    }

    /* One row mapper, not three copies. The three-copy version was already a
       smell and became a liability the moment the price column was renamed. */
    private static SeatView toSeatView(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new SeatView(
                rs.getLong("seat_id"),
                rs.getString("row_label"),
                rs.getInt("seat_number"),
                SeatStatus.valueOf(rs.getString("status")),
                rs.getString("tier"),
                rs.getString("tier_name"),
                rs.getInt("base_price_minor"),
                rs.getString("currency"));
    }

    private boolean showtimeExists(long showtimeId) {
        return Boolean.TRUE.equals(jdbc.sql(SHOWTIME_EXISTS)
                .param("showtimeId", showtimeId)
                .query(Boolean.class)
                .single());
    }
}