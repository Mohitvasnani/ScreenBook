package com.mohit.screenbook.booking;

import com.mohit.screenbook.catalogue.SeatMapQuery;
import com.mohit.screenbook.catalogue.SeatView;
import com.mohit.screenbook.common.HoldAlreadyConvertedException;
import com.mohit.screenbook.common.HoldExpiredException;
import com.mohit.screenbook.common.NotFoundException;
import com.mohit.screenbook.hold.HoldSeat;
import com.mohit.screenbook.hold.SeatHold;
import com.mohit.screenbook.hold.SeatHoldRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BookingService {

    private final SeatHoldRepository holds;
    private final SeatMapQuery seatMap;
    private final BookingRepository bookings;
    private final BookingQuery bookingQuery;
    private final Clock clock;

    BookingService(SeatHoldRepository holds, SeatMapQuery seatMap,
                   BookingRepository bookings, BookingQuery bookingQuery, Clock clock) {
        this.holds = holds;
        this.seatMap = seatMap;
        this.bookings = bookings;
        this.bookingQuery = bookingQuery;
        this.clock = clock;
    }

    /**
     * Converts a hold into a booking, every seat on it or none.
     *
     * The state check below is the readable rule; it is not what makes this
     * safe. Two requests can both read an ACTIVE hold before either writes.
     * What makes it safe is @Version on the hold: the UPDATE carries
     * AND version = ?, so the second writer affects zero rows and loses.
     *
     * Ownership is decided here rather than on the hold. A guest can hold
     * seats, sign in, and confirm, and the booking is theirs: who you are at
     * the moment you commit is what matters, not who you were while browsing.
     */
    @Transactional
    public BookingResponse confirm(UUID holdId, UUID userId, String email) {
        SeatHold hold = holds.findById(holdId)
                .orElseThrow(() -> new NotFoundException(
                        "No hold with id %s.".formatted(holdId)));

        Instant now = clock.instant();
        SeatHold.State state = hold.stateAt(now);
        if (state == SeatHold.State.CONVERTED) {
            throw new HoldAlreadyConvertedException(
                    "That hold has already been converted into a booking.");
        }
        if (state != SeatHold.State.ACTIVE) {
            throw new HoldExpiredException();
        }

        /* Read inside this transaction: the hold's seats are a lazy collection
           and spring.jpa.open-in-view=false closes the persistence context at
           the transaction boundary. */
        List<Long> seatIds = hold.getSeats().stream()
                .map(HoldSeat::getSeatId)
                .sorted()
                .toList();

        List<SeatView> rows = seatMap.seatsByIds(hold.getShowtimeId(), seatIds);
        if (rows.size() != seatIds.size()) {
            throw new IllegalStateException(
                    "Hold %s references seats that do not exist".formatted(holdId));
        }

               /* The prices come off the hold, never from the seat map. This is the
           single change the whole wave is for. The map's price moves with
           occupancy, so re-reading it here would charge a number the customer
           never saw, and the seat map read above now exists only to supply the
           currency and to prove the seats still exist. */
        Map<Long, Integer> prices = hold.getSeats().stream()
                .collect(Collectors.toMap(HoldSeat::getSeatId, HoldSeat::getPriceMinor));

        hold.markBooked(now);

        Booking booking = Booking.of(
                hold.getId(), hold.getShowtimeId(),
                prices, rows.getFirst().currency(),
                BookingReference.generate(), userId, email, now);

        try {
            /* saveAndFlush, not save. JPA would otherwise defer both statements
               to commit, which happens after this method returns, and the
               exceptions below could not be caught here at all. */
            holds.saveAndFlush(hold);
            bookings.saveAndFlush(booking);
        } catch (OptimisticLockingFailureException e) {
            throw new HoldAlreadyConvertedException(
                    "That hold has already been converted into a booking.");
        } catch (DataIntegrityViolationException e) {
            /* UNIQUE (hold_id) on booking, or UNIQUE (showtime_id, seat_id) on
               booking_seat. Reaching here means the version check was bypassed
               somehow, so the database saved us. */
            throw new HoldAlreadyConvertedException(
                    "That hold has already been converted into a booking.");
        }

        return bookingQuery.byReference(booking.getReference());
    }

    @Transactional(readOnly = true)
    public BookingResponse find(String reference) {
        return bookingQuery.byReference(reference);
    }

    /** Signed-in bookings only. See BookingQuery.forUser. */
    @Transactional(readOnly = true)
    public List<BookingResponse> forUser(UUID userId) {
        return bookingQuery.forUser(userId);
    }
}