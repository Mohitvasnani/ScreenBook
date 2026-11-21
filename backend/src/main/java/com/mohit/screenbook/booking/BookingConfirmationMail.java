package com.mohit.screenbook.booking;

import com.mohit.screenbook.auth.AppUser;
import com.mohit.screenbook.auth.AppUserRepository;
import com.mohit.screenbook.common.Emails;
import com.mohit.screenbook.mail.BookingConfirmation;
import com.mohit.screenbook.mail.Mailer;
import org.slf4j.Logger;
import java.time.ZoneId;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the confirmation once the booking has committed.
 *
 * @TransactionalEventListener runs after the publishing transaction commits
 * and not at all if it rolls back, so nobody is mailed about a booking that
 * does not exist. @Async moves it off the request thread: a slow or dead
 * provider does not slow the 201.
 *
 * Nothing escapes. The booking is the record and the mail is a courtesy about
 * it, so a failure is logged and the booking stands. What this leaves open is
 * a crash between the commit and the send; an outbox table would close it.
 */
@Component
public class BookingConfirmationMail {

    private static final Logger log = LoggerFactory.getLogger(BookingConfirmationMail.class);

    private final BookingQuery bookings;
    private final AppUserRepository users;
    private final Mailer mailer;
    private final String linkBase;

    BookingConfirmationMail(BookingQuery bookings, AppUserRepository users, Mailer mailer,
                            @Value("${screenbook.booking.link-base}") String linkBase) {
        this.bookings = bookings;
        this.users = users;
        this.mailer = mailer;
        this.linkBase = linkBase;
    }

    @Async
    @TransactionalEventListener
    public void send(BookingConfirmed event) {
        try {
            String to = recipient(event);
            if (to == null) {
                log.warn("No address for booking {}, confirmation not sent", event.reference());
                return;
            }

            BookingResponse booking = bookings.byReference(event.reference());
            BookingQuery.Venue venue = bookings.venueOf(booking.showtimeId());

            mailer.sendBookingConfirmation(to, new BookingConfirmation(
                    booking.bookingReference(),
                    "%s/%s".formatted(linkBase, booking.bookingReference()),
                    booking.filmTitle(),
                    venue.cinemaName(),
                    booking.startsAt().atZone(venue.zone()),
                    booking.seats().stream()
                            .map(seat -> seat.rowLabel() + seat.seatNumber())
                            .toList(),
                    booking.totalPaidMinor(),
                    booking.currency()));
        } catch (RuntimeException e) {
            log.warn("Confirmation mail for booking {} was not sent", event.reference(), e);
        }
    }

    /* The address typed at checkout if there is one, otherwise the account's. */
    private String recipient(BookingConfirmed event) {
        String typed = Emails.normalise(event.email());
        if (typed != null) {
            return typed;
        }
        if (event.userId() == null) {
            return null;
        }
        return users.findById(event.userId()).map(AppUser::getEmail).orElse(null);
    }
}