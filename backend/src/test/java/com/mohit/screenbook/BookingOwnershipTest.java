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

import java.util.List;
import java.util.UUID;

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
                        SELECT showtime_id, seat_id
                        FROM seat_availability
                        WHERE status = 'AVAILABLE'
                        LIMIT 1
                        """)
                .query((rs, rowNum) -> new Target(
                        rs.getLong("showtime_id"), rs.getLong("seat_id")))
                .single();
    }
}
