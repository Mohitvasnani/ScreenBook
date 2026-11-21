package com.mohit.screenbook;

import com.mohit.screenbook.auth.AuthService;
import com.mohit.screenbook.auth.SignupRequest;
import com.mohit.screenbook.booking.BookingResponse;
import com.mohit.screenbook.booking.BookingService;
import com.mohit.screenbook.hold.HoldResponse;
import com.mohit.screenbook.hold.HoldService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The booking_overview view, which exists for a person reading the data in
 * psql or Neon and which nothing in the application uses. The risk in it is the
 * join: booking_seat fans a booking out to one row per seat, and a wrong
 * GROUP BY or a wrong join type would show up as repeated bookings or missing
 * ones, with every query still running without error.
 *
 * Not @Transactional, for the same reason as BookingOwnershipTest: the
 * services open their own transactions and the rows have to be committed for
 * the view to see them. Each test books fresh seats and leaves the rows behind.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class BookingOverviewTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    AuthService auth;

    @Autowired
    HoldService holds;

    @Autowired
    BookingService bookings;

    @Autowired
    JdbcClient jdbc;

    /**
     * The fan-out check. Two seats must still be one row, with both seats named
     * and the total taken from the lines, not multiplied by the join.
     */
    @Test
    @DisplayName("a two-seat booking is one row with both seats and the summed total")
    void oneRowPerBooking() {
        String email = freshEmail();
        AuthService.Session session = auth.signUp(new SignupRequest(email, PASSWORD, "Test User"));
        BookingResponse booked = bookTwoSeats(session.user().getId(), email);

        List<OverviewRow> rows = overviewOf(booked.bookingReference());

        /* Exactly one, not "contains": a row per seat is the bug this exists
           to catch. */
        assertEquals(1, rows.size(), "the view should have one row per booking");
        OverviewRow row = rows.getFirst();

        assertEquals(2, row.seatCount());
        assertEquals(seatNames(booked), row.seats());
        assertEquals(0, BigDecimal.valueOf(booked.totalPaidMinor(), 2).compareTo(row.total()),
                "the total should be the sum of the seat lines, in major units");
        assertEquals(booked.currency(), row.currency());
        assertEquals("Test User", row.customer());
        assertEquals(email, row.contactEmail());
        assertTrue(row.hasAccount());
    }

    /**
     * The LEFT JOIN check. A guest has no app_user row, so an inner join would
     * drop the booking from the view without any error. Asserting that the row
     * exists is the test; the other columns say what it should look like.
     */
    @Test
    @DisplayName("a guest booking is still in the view, with the address from checkout")
    void guestBookingIsNotDropped() {
        String email = freshEmail();
        BookingResponse booked = bookTwoSeats(null, email);

        List<OverviewRow> rows = overviewOf(booked.bookingReference());

        assertEquals(1, rows.size(), "a guest booking must not be dropped by the join");
        OverviewRow row = rows.getFirst();

        assertEquals("(guest)", row.customer());
        assertEquals(email, row.contactEmail());
        assertFalse(row.hasAccount());
    }

    // helpers

    private String freshEmail() {
        return "overview-" + UUID.randomUUID() + "@example.com";
    }

    /**
     * Two seats on one screening, as a guest hold. Ownership is applied at
     * confirm, which is where userId comes in (null for a guest).
     */
    private BookingResponse bookTwoSeats(UUID userId, String email) {
        Target first = jdbc.sql("""
                        SELECT sa.showtime_id, sa.seat_id
                        FROM seat_availability sa
                        JOIN showtime st ON st.id = sa.showtime_id
                        WHERE sa.status = 'AVAILABLE'
                          AND st.starts_at > now()
                        LIMIT 1
                        """)
                .query((rs, rowNum) -> new Target(
                        rs.getLong("showtime_id"), rs.getLong("seat_id")))
                .single();

        long secondSeat = jdbc.sql("""
                        SELECT seat_id
                        FROM seat_availability
                        WHERE showtime_id = :showtimeId
                          AND status = 'AVAILABLE'
                          AND seat_id <> :seatId
                        LIMIT 1
                        """)
                .param("showtimeId", first.showtimeId())
                .param("seatId", first.seatId())
                .query(Long.class)
                .single();

        HoldResponse hold = holds.claim(
                first.showtimeId(), List.of(first.seatId(), secondSeat), null);
        return bookings.confirm(hold.holdId(), userId, email);
    }

    /** "A2, A3", in the order BookingResponse already sorts them. */
    private static String seatNames(BookingResponse booked) {
        return booked.seats().stream()
                .map(seat -> seat.rowLabel() + seat.seatNumber())
                .collect(Collectors.joining(", "));
    }

    private List<OverviewRow> overviewOf(String reference) {
        return jdbc.sql("""
                        SELECT seats, seat_count, total, currency,
                               customer, contact_email, has_account
                        FROM booking_overview
                        WHERE reference = :reference
                        """)
                .param("reference", reference)
                .query((rs, rowNum) -> new OverviewRow(
                        rs.getString("seats"),
                        rs.getInt("seat_count"),
                        rs.getBigDecimal("total"),
                        rs.getString("currency"),
                        rs.getString("customer"),
                        rs.getString("contact_email"),
                        rs.getBoolean("has_account")))
                .list();
    }

    private record Target(long showtimeId, long seatId) {
    }

    private record OverviewRow(
            String seats,
            int seatCount,
            BigDecimal total,
            String currency,
            String customer,
            String contactEmail,
            boolean hasAccount
    ) {
    }
}