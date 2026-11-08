package com.mohit.screenbook.booking;

import com.mohit.screenbook.common.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One query shape serves the 201 body, GET by reference, and the my-bookings
 * list, so none of the three can drift from the others. No @Transactional of
 * its own: it joins whatever transaction the caller is in, which matters
 * because the POST path reads back a booking it has only just inserted.
 */
@Repository
public class BookingQuery {

    /**
     * Joining booking to booking_seat fans the result out: one row per seat,
     * with the booking's own columns repeated. That is normal for a
     * parent-child join and it is why the rows are folded back together in
     * Java rather than mapped one to one.
     */
    private static final String SELECT = """
            SELECT b.reference         AS reference,
                   b.showtime_id       AS showtime_id,
                   f.title             AS film_title,
                   sc.name             AS screen_name,
                   st.starts_at        AS starts_at,
                   b.currency          AS currency,
                   b.booked_at         AS booked_at,
                   s.row_label         AS row_label,
                   s.seat_number       AS seat_number,
                   s.tier              AS tier,
                   bs.price_paid_minor AS price_paid_minor
            FROM booking b
            JOIN booking_seat bs ON bs.booking_id = b.id
            JOIN seat         s  ON s.id          = bs.seat_id
            JOIN showtime     st ON st.id         = b.showtime_id
            JOIN film         f  ON f.id          = st.film_id
            JOIN screen       sc ON sc.id         = st.screen_id
            """;

    private static final String BY_REFERENCE = SELECT + """
            WHERE b.reference = :reference
            ORDER BY s.row_label, s.seat_number
            """;

    /**
     * user_id only, never email. booking.email is unverified: matching on it
     * would let anyone read a stranger's history by signing up with their
     * address.
     *
     * Ordered newest booking first, and within a booking by seat, so the fold
     * below keeps both orders without sorting anything in Java.
     */
    private static final String BY_USER = SELECT + """
            WHERE b.user_id = :userId
            ORDER BY b.booked_at DESC, b.reference, s.row_label, s.seat_number
            """;

    private final JdbcClient jdbc;

    BookingQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    BookingResponse byReference(String reference) {
        List<Row> rows = jdbc.sql(BY_REFERENCE)
                .param("reference", reference)
                .query(BookingQuery::toRow)
                .list();

        if (rows.isEmpty()) {
            throw new NotFoundException(
                    "No booking with reference %s.".formatted(reference));
        }
        return fold(rows);
    }

    List<BookingResponse> forUser(UUID userId) {
        List<Row> rows = jdbc.sql(BY_USER)
                .param("userId", userId)
                .query(BookingQuery::toRow)
                .list();

        /* Grouped by reference rather than by index, and into a LinkedHashMap
           so the newest-first order from the query survives the grouping. */
        Map<String, List<Row>> byBooking = new LinkedHashMap<>();
        for (Row row : rows) {
            byBooking.computeIfAbsent(row.reference(), key -> new ArrayList<>()).add(row);
        }

        return byBooking.values().stream().map(BookingQuery::fold).toList();
    }

    private static BookingResponse fold(List<Row> rows) {
        /* Every row carries the same booking columns, so the first speaks for
           the header. */
        Row head = rows.getFirst();

        return new BookingResponse(
                head.reference(),
                head.showtimeId(),
                head.filmTitle(),
                head.screenName(),
                head.startsAt(),
                rows.stream()
                        .map(row -> new BookingResponse.BookedSeat(
                                row.rowLabel(), row.seatNumber(),
                                row.tier(), row.pricePaidMinor()))
                        .toList(),
                rows.stream().mapToInt(Row::pricePaidMinor).sum(),
                head.currency(),
                head.bookedAt());
    }

    private static Row toRow(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getString("reference"),
                rs.getLong("showtime_id"),
                rs.getString("film_title"),
                rs.getString("screen_name"),
                rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getString("currency"),
                rs.getObject("booked_at", OffsetDateTime.class).toInstant(),
                rs.getString("row_label"),
                rs.getInt("seat_number"),
                rs.getString("tier"),
                rs.getInt("price_paid_minor"));
    }

    /** One joined row, before the fan-out is folded back into bookings. */
    private record Row(
            String reference,
            long showtimeId,
            String filmTitle,
            String screenName,
            Instant startsAt,
            String currency,
            Instant bookedAt,
            String rowLabel,
            int seatNumber,
            String tier,
            int pricePaidMinor
    ) {
    }
}