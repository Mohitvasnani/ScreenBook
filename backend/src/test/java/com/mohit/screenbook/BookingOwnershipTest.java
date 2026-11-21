package com.mohit.screenbook;

import com.mohit.screenbook.auth.AuthService;
import com.mohit.screenbook.auth.SignupRequest;
import com.mohit.screenbook.booking.BookingResponse;
import com.mohit.screenbook.booking.BookingService;
import com.mohit.screenbook.booking.IdempotentBookings;
import com.mohit.screenbook.common.IdempotencyKeyReusedException;
import com.mohit.screenbook.hold.HoldResponse;
import com.mohit.screenbook.hold.HoldService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.mohit.screenbook.common.ValidationFailedException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who a booking belongs to, and who can therefore see it.
 *
 * Not @Transactional, for the same reason ConcurrencyTest is not: these call
 * services that open their own transactions, and a rollback at the end would
 * hide whether anything was actually written. Rows are left behind. Every
 * test takes a fresh seat from the availability view, which already excludes
 * what an earlier test claimed.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class BookingOwnershipTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    AuthService auth;

    @Autowired
    HoldService holds;

    @Autowired
    BookingService bookings;

    @Autowired
    IdempotentBookings idempotent;

    @Autowired
    JdbcClient jdbc;

    /**
     * Holds as a guest and confirms as a member, which is the decision this
     * pins: ownership is settled when you commit, not when you start
     * browsing. Someone can pick seats, sign in, and the booking is theirs.
     */
    @Test
    @DisplayName("a booking confirmed while signed in appears in my bookings")
    void signedInBookingIsMine() {
        AuthService.Session session = signUp(freshEmail());
        UUID userId = session.user().getId();

        BookingResponse booked = bookAs(userId, session.user().getEmail());

        List<BookingResponse> mine = bookings.forUser(userId);

        /* Exactly one, not "contains". The account was created in this test,
           so anything else in the list would mean the query is matching rows
           it has no business matching. */
        assertEquals(1, mine.size(), "a new account should see exactly its one booking");
        assertEquals(booked.bookingReference(), mine.getFirst().bookingReference());
    }

    /**
     * The security decision, and the reason the query matches on user_id
     * alone. booking.email is whatever was typed at checkout and nobody has
     * proved they own it, so matching on it would hand a stranger's booking
     * history to anyone willing to sign up with their address.
     */
    @Test
    @DisplayName("a guest booking with my exact address is still not mine")
    void guestBookingWithMatchingEmailIsNotMine() {
        String email = freshEmail();
        AuthService.Session session = signUp(email);
        UUID userId = session.user().getId();

        /* Typed in a different case on purpose. Both sides go through
           Emails.normalise, so by the time it is stored it is byte for byte
           the address on the account. The exclusion has to survive that, not
           pass because two strings happened to differ. */
        BookingResponse guest = bookAs(null, email.toUpperCase());

        /* The premise, asserted rather than assumed. An empty list only
           proves something is being excluded if the thing exists in the first
           place: without these two checks, a bug that silently failed to
           write the booking would make this test green while proving nothing. */
        assertNotNull(bookings.find(guest.bookingReference()),
                "the guest booking should exist");
        assertEquals(session.user().getEmail(), storedEmail(guest.bookingReference()),
                "and should carry the same address as the account");

        assertTrue(bookings.forUser(userId).isEmpty(),
                "an unverified address must not pull a stranger's booking into my history");
    }

    /**
     * The idempotency hash covers holdId, userId and email rather than the
     * hold alone. Without the user in it, this second call would find a
     * matching hash on a finished claim, take the replay path, and hand user
     * A's booking to user B.
     */
    @Test
    @DisplayName("the same key with a different user is reuse, not a replay")
    void sameKeyDifferentUserIsReuse() {
        UUID userA = signUp(freshEmail()).user().getId();
        UUID userB = signUp(freshEmail()).user().getId();

        UUID holdId = guestHold();
        String key = "cross-user-" + UUID.randomUUID();

        idempotent.confirm(holdId, key, userA, null);

        /* Same hold, not a second one. Two holds is what the existing reuse
           test covers and it would pass whether or not the user is in the
           hash; one hold leaves the user as the only thing that changed.
           Nothing reaches the database on this call, which is why user B
           never gets a row of its own. */
        assertThrows(IdempotencyKeyReusedException.class,
                () -> idempotent.confirm(holdId, key, userB, null),
                "a key tied to another account must be refused, never replayed");
    }

    /**
     * The rule that a booking needs an owner or an address. The last step is
     * the point: the refusal happens before anything is written, so the same
     * hold is still good once an address is supplied.
     */
    @Test
    @DisplayName("a booking with no account and no address is refused before anything is written")
    void guestBookingNeedsAnEmail() {
        UUID holdId = guestHold();

        assertThrows(ValidationFailedException.class,
                () -> bookings.confirm(holdId, null, null));
        assertThrows(ValidationFailedException.class,
                () -> bookings.confirm(holdId, null, "   "));

        assertNotNull(bookings.confirm(holdId, null, freshEmail()));
    }


    /**
     * The boundary is the screening's start, so the same booking has to move
     * from one list to the other when the start passes. Each list is asserted
     * on both sides: "upcoming has it" alone would pass if upcoming returned
     * everything.
     */
    @Test
    @DisplayName("a booking is upcoming until its screening starts, then past")
    void bookingMovesFromUpcomingToPast() {
        AuthService.Session session = signUp(freshEmail());
        UUID userId = session.user().getId();
        BookingResponse booked = bookAs(userId, session.user().getEmail());
        OffsetDateTime original = startOf(booked.showtimeId());

        try {
            /* The premise: someAvailableSeat only offers screenings in the
               future, so the booking starts out upcoming. The account is new,
               so these are exact lists, not "contains". */
            assertEquals(List.of(booked.bookingReference()),
                    references(bookings.upcomingForUser(userId)));
            assertTrue(bookings.pastForUser(userId).isEmpty(),
                    "a screening in the future is not past");

            startsInHours(booked.showtimeId(), -2);

            assertTrue(bookings.upcomingForUser(userId).isEmpty(),
                    "a screening that has started is not upcoming");
            assertEquals(List.of(booked.bookingReference()),
                    references(bookings.pastForUser(userId)));
        } finally {
            putBack(booked.showtimeId(), original);
        }
    }

    /**
     * Upcoming is soonest first, past is most recent first. The times are
     * chosen so neither list matches booking order (booked_at DESC, which is
     * what the old list used), so an ordering that came from the wrong column
     * would fail rather than agree by accident.
     */
    @Test
    @DisplayName("upcoming is soonest first and past is most recent first")
    void eachListIsInTheOrderItsReaderWants() {
        AuthService.Session session = signUp(freshEmail());
        UUID userId = session.user().getId();
        String email = session.user().getEmail();

        BookingResponse first = bookAs(userId, email);
        BookingResponse second = bookElsewhere(userId, email, first.showtimeId());
        OffsetDateTime firstOriginal = startOf(first.showtimeId());
        OffsetDateTime secondOriginal = startOf(second.showtimeId());

        try {
            /* first was booked first and is the sooner screening, so soonest
               first gives [first, second] where newest booking first would
               give [second, first]. */
            startsInHours(first.showtimeId(), 24);
            startsInHours(second.showtimeId(), 48);
            assertEquals(List.of(first.bookingReference(), second.bookingReference()),
                    references(bookings.upcomingForUser(userId)));

            /* first is now the more recent of the two past screenings, so
               most recent first gives [first, second] again. */
            startsInHours(first.showtimeId(), -24);
            startsInHours(second.showtimeId(), -48);
            assertEquals(List.of(first.bookingReference(), second.bookingReference()),
                    references(bookings.pastForUser(userId)));
        } finally {
            putBack(first.showtimeId(), firstOriginal);
            putBack(second.showtimeId(), secondOriginal);
        }
    }

    // helpers

    private String freshEmail() {
        return "owner-" + UUID.randomUUID() + "@example.com";
    }

    private AuthService.Session signUp(String email) {
        return auth.signUp(new SignupRequest(email, PASSWORD, "Test User"));
    }

    /** A guest hold on a fresh seat. Ownership is applied at confirm instead. */
    private UUID guestHold() {
        Target target = someAvailableSeat();
        HoldResponse hold = holds.claim(target.showtimeId(), List.of(target.seatId()), null);
        return hold.holdId();
    }

    private BookingResponse bookAs(UUID userId, String email) {
        return bookings.confirm(guestHold(), userId, email);
    }

    /**
     * Read back from the column rather than the response, because
     * BookingResponse deliberately does not expose the address. Direct SQL is
     * the point here: the test is about what is in the row.
     */
    private String storedEmail(String reference) {
        return jdbc.sql("SELECT email FROM booking WHERE reference = :reference")
                .param("reference", reference)
                .query(String.class)
                .single();
    }

    private record Target(long showtimeId, long seatId) {
    }

    private Target someAvailableSeat() {
        return jdbc.sql("""
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
    }


    /**
     * Books a seat on a screening other than the one given, so two bookings
     * land on two screenings and their order can be told apart.
     */
    private BookingResponse bookElsewhere(UUID userId, String email, long notShowtimeId) {
        Target target = jdbc.sql("""
                        SELECT sa.showtime_id, sa.seat_id
                        FROM seat_availability sa
                        JOIN showtime st ON st.id = sa.showtime_id
                        WHERE sa.status = 'AVAILABLE'
                          AND st.starts_at > now()
                          AND sa.showtime_id <> :showtimeId
                        LIMIT 1
                        """)
                .param("showtimeId", notShowtimeId)
                .query((rs, rowNum) -> new Target(
                        rs.getLong("showtime_id"), rs.getLong("seat_id")))
                .single();

        HoldResponse hold = holds.claim(target.showtimeId(), List.of(target.seatId()), null);
        return bookings.confirm(hold.holdId(), userId, email);
    }

    private OffsetDateTime startOf(long showtimeId) {
        return jdbc.sql("SELECT starts_at FROM showtime WHERE id = :id")
                .param("id", showtimeId)
                .query((rs, rowNum) -> rs.getObject("starts_at", OffsetDateTime.class))
                .single();
    }

    /**
     * Moves a screening to now plus the given hours, in SQL. Same approach as
     * PasswordResetTest: age the data rather than move the clock. The tests
     * put the original time back, so the shared database ends up as it was.
     */
    private void startsInHours(long showtimeId, int hours) {
        jdbc.sql("""
                        UPDATE showtime
                        SET starts_at = now() + make_interval(hours => :hours)
                        WHERE id = :id
                        """)
                .param("hours", hours)
                .param("id", showtimeId)
                .update();
    }

    private void putBack(long showtimeId, OffsetDateTime startsAt) {
        jdbc.sql("UPDATE showtime SET starts_at = :startsAt WHERE id = :id")
                .param("startsAt", startsAt)
                .param("id", showtimeId)
                .update();
    }

    private static List<String> references(List<BookingResponse> list) {
        return list.stream().map(BookingResponse::bookingReference).toList();
    }
}
