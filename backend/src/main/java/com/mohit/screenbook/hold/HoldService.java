package com.mohit.screenbook.hold;

import com.mohit.screenbook.catalogue.SeatMapQuery;
import com.mohit.screenbook.catalogue.SeatRepository;
import com.mohit.screenbook.catalogue.SeatStatus;
import com.mohit.screenbook.catalogue.SeatView;
import com.mohit.screenbook.catalogue.ShowtimePricingInputs;
import com.mohit.screenbook.catalogue.ShowtimeQuery;
import com.mohit.screenbook.common.HoldAlreadyConvertedException;
import com.mohit.screenbook.common.NotFoundException;
import com.mohit.screenbook.common.SeatsUnavailableException;
import com.mohit.screenbook.common.UnavailableSeat;
import com.mohit.screenbook.common.ValidationFailedException;
import com.mohit.screenbook.pricing.PriceBreakdown;
import com.mohit.screenbook.pricing.PricingRules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class HoldService {

    private final SeatRepository seats;
    private final SeatMapQuery seatMap;
    private final ShowtimeQuery showtimes;
    private final SeatHoldRepository holds;
    private final PricingRules pricing;
    private final Clock clock;
    private final Duration ttl;
    private final int guestMaxSeats;
    private final int memberMaxSeats;

    HoldService(SeatRepository seats, SeatMapQuery seatMap, ShowtimeQuery showtimes,
                SeatHoldRepository holds, PricingRules pricing, Clock clock,
                @Value("${screenbook.hold.ttl-seconds}") long ttlSeconds,
                @Value("${screenbook.holds.max-seats-guest}") int guestMaxSeats,
                @Value("${screenbook.holds.max-seats-member}") int memberMaxSeats) {
        this.seats = seats;
        this.seatMap = seatMap;
        this.showtimes = showtimes;
        this.holds = holds;
        this.pricing = pricing;
        this.clock = clock;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.guestMaxSeats = guestMaxSeats;
        this.memberMaxSeats = memberMaxSeats;
    }

    /**
     * Claims adjacent seats for the TTL, all or nothing.
     *
     * Locks first, then checks, then prices, then inserts, all in one
     * transaction. Locking the seat rows before reading availability means
     * that between our check and our insert, no other transaction can run its
     * own check for those seats. A loser blocks on the lock, wakes up after
     * the winner commits, sees the seats are now HELD, and gets a 409.
     *
     * The locks are taken in ascending seat id order, and that is the whole
     * reason this does not deadlock. Two requests for [5, 9] and [9, 5] taken
     * in the order asked would each hold one seat and wait for the other, and
     * Postgres would break the tie a second later by killing one with SQLSTATE
     * 40P01. A global order makes the cycle unformable: the second request
     * blocks on the lowest seat before it holds anything at all.
     *
     * One lock per statement rather than one IN query, because ORDER BY
     * describes the order rows are returned, not the order they are locked.
     * The lock step can sit below the sort in the plan, so a plan change could
     * silently reintroduce the deadlock. Six round trips inside one
     * transaction is a cheap price for an order that is actually guaranteed.
     *
     * userId is null for a guest. That is the only thing this needs to know
     * about identity, and it arrives as a parameter rather than being read
     * from SecurityContextHolder so the service stays testable without a
     * security context.
     */
    @Transactional
    public HoldResponse claim(long showtimeId, List<Long> seatIds, UUID userId) {

        /* A repeated seat is a client bug, not a conflict, so it is rejected
           rather than deduped. Reported as a validation failure because that is
           what it is: the same category as asking for seven seats. */
        Set<Long> distinct = new LinkedHashSet<>(seatIds);
        if (distinct.size() != seatIds.size()) {
            throw new ValidationFailedException("seatIds must not repeat a seat.");
        }

        /* The cap depends on who is asking, which is why it lives here rather
           than on the request record: a validation annotation runs before
           anything knows whether there is a session.

           Checked before the locks are taken, because a request that was never
           going to be allowed should not make anyone else wait on a seat row. */
        int cap = userId == null ? guestMaxSeats : memberMaxSeats;
        if (seatIds.size() > cap) {
            throw new ValidationFailedException(userId == null
                    ? "Guests can book up to %d seats. Sign in to book up to %d."
                    .formatted(guestMaxSeats, memberMaxSeats)
                    : "A booking can cover at most %d seats.".formatted(memberMaxSeats));
        }

        // 1. Lock, in ascending id order. Doubles as proof each seat exists.
        List<Long> ordered = distinct.stream().sorted().toList();
        for (Long seatId : ordered) {
            seats.findByIdForUpdate(seatId)
                    .orElseThrow(() -> new NotFoundException(
                            "No seat with id %d.".formatted(seatId)));
        }

        // 2. Read availability and listed prices inside the locks, in one query.
        List<SeatView> rows = seatMap.seatsByIds(showtimeId, ordered);

        /* The view joins seat to showtime on screen_id, so a seat that is not
           in this showtime's screen returns no row at all. Missing rows are a
           404 rather than a 409: a seat that was never there is a different
           mistake from a seat that has gone. */
        if (rows.size() != ordered.size()) {
            throw new NotFoundException(
                    "One or more seats do not belong to showtime %d.".formatted(showtimeId));
        }

        requireAdjacent(rows);

        List<UnavailableSeat> unavailable = rows.stream()
                .filter(row -> row.status() != SeatStatus.AVAILABLE)
                .map(row -> new UnavailableSeat(
                        row.id(), row.rowLabel(), row.seatNumber(), row.status().name()))
                .toList();

        /* All or nothing. Throwing here means nothing was written, so the
           rollback has nothing to undo; @Transactional is the backstop rather
           than the mechanism. */
        if (!unavailable.isEmpty()) {
            throw new SeatsUnavailableException(seatIds.size(), unavailable);
        }

        // 3. Price it, freeze it, insert. The locks release when this commits.
        Instant now = clock.instant();

        /* Occupancy is read here, inside the locks and before the insert, so
           the figure that sets the price is the one that was true at the
           moment of the claim. Reading it earlier would price against a house
           that has since filled; reading it after the insert would count this
           request's own seats and let a customer inflate their own price. */
        ShowtimePricingInputs inputs = showtimes.pricingInputsFor(showtimeId);

        /* Every row carries the same listed price, because a hold covers one
           tier, so the first is the base for the whole hold. */
        PriceBreakdown price = pricing.priceFor(
                rows.getFirst().priceMinor(),
                inputs.seatsTaken(), inputs.seatsTotal(),
                inputs.startsAt(), now);

        SeatHold hold = holds.save(
                SeatHold.claim(showtimeId, ordered, price, now, ttl));

        Map<Long, Integer> frozen = frozenPrices(hold);
        return new HoldResponse(
                hold.getId(), showtimeId,
                seatViews(rows, frozen), total(frozen), rows.getFirst().currency(),
                hold.getExpiresAt(), ttl.toSeconds());
    }

    /**
     * One hold, so the client's URL survives a refresh.
     *
     * The seats come from the hold's own collection, which is lazy: this method
     * is transactional and with spring.jpa.open-in-view=false the collection
     * has to be read before the transaction ends. Reading it here rather than
     * in the controller is what keeps that true.
     *
     * The prices come off the hold, not the view. That is the point of this
     * wave: the view's price moves with occupancy, so a checkout screen
     * reloaded four minutes in would otherwise quote a different number from
     * the one that was agreed. The view is still read, because it is the only
     * thing that knows a seat's row and number.
     */
    @Transactional(readOnly = true)
    public HoldDetailResponse get(UUID holdId) {
        SeatHold hold = holds.findById(holdId)
                .orElseThrow(() -> new NotFoundException(
                        "No hold with id %s.".formatted(holdId)));

        List<Long> seatIds = hold.getSeats().stream()
                .map(HoldSeat::getSeatId)
                .sorted()
                .toList();

        List<SeatView> rows = seatMap.seatsByIds(hold.getShowtimeId(), seatIds);
        if (rows.size() != seatIds.size()) {
            throw new IllegalStateException(
                    "Hold %s references seats that do not exist".formatted(holdId));
        }

        Instant now = clock.instant();
        Map<Long, Integer> frozen = frozenPrices(hold);
        return new HoldDetailResponse(
                hold.getId(), hold.getShowtimeId(),
                seatViews(rows, frozen), total(frozen), rows.getFirst().currency(),
                hold.getExpiresAt(), hold.remainingSeconds(now),
                apiStatus(hold.stateAt(now)));
    }

    /**
     * Hands seats back early rather than leaving them locked for the rest of
     * their five minutes. One hold, one release: there is no releasing a single
     * seat out of a hold, because a hold is atomic in both directions.
     */
    @Transactional
    public void release(UUID holdId) {
        Instant now = clock.instant();

        if (holds.releaseIfActive(holdId, now) == 1) {
            return;
        }

        /* Nothing was updated, so either there is no such hold or it had
           already ended. One read tells us which, and only on this cold path. */
        SeatHold hold = holds.findById(holdId)
                .orElseThrow(() -> new NotFoundException(
                        "No hold with id %s.".formatted(holdId)));

        if (hold.stateAt(now) == SeatHold.State.CONVERTED) {
            throw new HoldAlreadyConvertedException(
                    "That hold is already a booking and cannot be released.");
        }

        /* Expired or already released. The seats are free, which is what the
           caller wanted, so DELETE reports success instead of inventing a
           conflict. Deleting something already gone is not a failure. */
    }

    /**
     * One row, no gaps.
     *
     * A group books in order to sit together, so seats scattered across the
     * house is not a booking anyone actually wants, and allowing it leaves the
     * house full of unsellable single gaps.
     *
     * Aisles deliberately do NOT break a run: seats 3 and 4 have a walking
     * aisle between them and still count as neighbours. Treating the aisle as a
     * break would confine every group of four or more to the six-wide centre
     * block, which is a worse outcome than two of them stepping over an aisle.
     *
     * Duplicates are already rejected above, so a run of n distinct seat
     * numbers is contiguous exactly when max - min + 1 == n.
     *
     * Same row also means same tier, because tier is assigned by row band.
     * That is what makes one price breakdown correct for a whole hold.
     */
    private static void requireAdjacent(List<SeatView> rows) {
        if (rows.size() == 1) return;

        String row = rows.getFirst().rowLabel();
        if (rows.stream().anyMatch(seat -> !seat.rowLabel().equals(row))) {
            throw new ValidationFailedException("Seats must all be in the same row.");
        }

        int lowest = rows.stream().mapToInt(SeatView::seatNumber).min().orElseThrow();
        int highest = rows.stream().mapToInt(SeatView::seatNumber).max().orElseThrow();
        if (highest - lowest + 1 != rows.size()) {
            throw new ValidationFailedException(
                    "Seats must be next to each other, with no gaps between them.");
        }
    }

    /* Labels come from the view, prices come from the hold. The view is the
       only thing that knows a seat's row and number; the hold is the only
       thing that knows what was quoted. Rows arrive ordered by row then seat
       number from the query, so this preserves display order rather than
       deciding it. */
    private static List<HeldSeatView> seatViews(List<SeatView> rows,
                                                Map<Long, Integer> frozen) {
        return rows.stream()
                .map(row -> new HeldSeatView(
                        row.id(), row.rowLabel(), row.seatNumber(),
                        row.tier(), frozen.get(row.id())))
                .toList();
    }

    private static Map<Long, Integer> frozenPrices(SeatHold hold) {
        return hold.getSeats().stream()
                .collect(Collectors.toMap(HoldSeat::getSeatId, HoldSeat::getPriceMinor));
    }

    private static int total(Map<Long, Integer> frozen) {
        return frozen.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static HoldStatus apiStatus(SeatHold.State state) {
        return switch (state) {
            case ACTIVE -> HoldStatus.ACTIVE;
            case CONVERTED -> HoldStatus.CONVERTED;
            case EXPIRED, RELEASED -> HoldStatus.EXPIRED;
        };
    }
}