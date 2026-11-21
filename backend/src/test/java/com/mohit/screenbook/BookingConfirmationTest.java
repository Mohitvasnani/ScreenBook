package com.mohit.screenbook;

import com.mohit.screenbook.auth.AuthService;
import com.mohit.screenbook.auth.SignupRequest;
import com.mohit.screenbook.booking.BookingResponse;
import com.mohit.screenbook.booking.BookingService;
import com.mohit.screenbook.hold.HoldService;
import com.mohit.screenbook.mail.BookingConfirmation;
import com.mohit.screenbook.mail.Mailer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The confirmation goes to the right address and a dead provider cannot touch
 * the booking. The send is asynchronous, so the mailer counts a latch down
 * when it is called and the tests wait on that instead of sleeping.
 *
 * Not @Transactional, like the other service-level tests: the listener only
 * fires after a real commit.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class BookingConfirmationTest {

    private static final String PASSWORD = "correct horse battery";

    record Sent(String to, BookingConfirmation booking) {
    }

    static class RecordingMailer implements Mailer {
        volatile CountDownLatch latch = new CountDownLatch(1);
        volatile boolean failing;
        final List<Sent> sent = new CopyOnWriteArrayList<>();

        @Override
        public void sendPasswordReset(String toEmail, String resetLink) {
        }

        @Override
        public void sendBookingConfirmation(String toEmail, BookingConfirmation booking) {
            try {
                if (failing) {
                    throw new IllegalStateException("provider down");
                }
                sent.add(new Sent(toEmail, booking));
            } finally {
                latch.countDown();
            }
        }
    }

    @TestConfiguration
    static class Mail {
        @Bean
        @Primary
        RecordingMailer recordingMailer() {
            return new RecordingMailer();
        }
    }

    @Autowired
    AuthService auth;

    @Autowired
    HoldService holds;

    @Autowired
    BookingService bookings;

    @Autowired
    RecordingMailer mailer;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void reset() {
        mailer.latch = new CountDownLatch(1);
        mailer.failing = false;
        mailer.sent.clear();
    }

    @Test
    @DisplayName("a guest booking is confirmed to the address typed at checkout")
    void guestGetsTheTypedAddress() throws Exception {
        String email = "guest-" + UUID.randomUUID() + "@example.com";

        BookingResponse booking = bookings.confirm(heldSeat(), null, email.toUpperCase());

        assertTrue(mailer.latch.await(5, TimeUnit.SECONDS), "the confirmation should be sent");
        Sent sent = mailer.sent.getFirst();
        assertEquals(email, sent.to());
        assertEquals(booking.bookingReference(), sent.booking().reference());
        assertTrue(sent.booking().link().endsWith("/" + booking.bookingReference()));
    }

    @Test
    @DisplayName("a signed-in booking is confirmed to the account's address")
    void memberGetsTheAccountAddress() throws Exception {
        String email = "member-" + UUID.randomUUID() + "@example.com";
        AuthService.Session session = auth.signUp(new SignupRequest(email, PASSWORD, "Test User"));

        bookings.confirm(heldSeat(), session.user().getId(), null);

        assertTrue(mailer.latch.await(5, TimeUnit.SECONDS), "the confirmation should be sent");
        assertEquals(email, mailer.sent.getFirst().to());
    }

    @Test
    @DisplayName("a mail provider that is down does not undo the booking")
    void failingProviderLeavesTheBooking() throws Exception {
        mailer.failing = true;

        BookingResponse booking = bookings.confirm(heldSeat(), null, "guest@example.com");

        assertTrue(mailer.latch.await(5, TimeUnit.SECONDS), "the send should have been attempted");
        assertTrue(mailer.sent.isEmpty());
        assertEquals(booking.bookingReference(),
                bookings.find(booking.bookingReference()).bookingReference());
    }

    private UUID heldSeat() {
        long[] target = jdbc.sql("""
                        SELECT sa.showtime_id, sa.seat_id
                        FROM seat_availability sa
                        JOIN showtime st ON st.id = sa.showtime_id
                        WHERE sa.status = 'AVAILABLE'
                          AND st.starts_at > now()
                        LIMIT 1
                        """)
                .query((rs, rowNum) -> new long[]{rs.getLong("showtime_id"), rs.getLong("seat_id")})
                .single();
        return holds.claim(target[0], List.of(target[1]), null).holdId();
    }
}