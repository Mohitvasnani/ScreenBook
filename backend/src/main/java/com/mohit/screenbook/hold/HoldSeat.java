package com.mohit.screenbook.hold;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;

/**
 * One seat inside a hold.
 *
 * @Embeddable rather than @Entity because this row has no identity of its own.
 * It cannot exist without its hold, nothing in the system points at one, and
 * the table's primary key is (hold_id, seat_id). Mapping it as an entity would
 * claim an identity that does not exist.
 *
 * equals and hashCode are mandatory here, not stylistic. Hibernate manages an
 * element collection by value equality, and the Set on SeatHold needs hashCode
 * to behave at all. An embeddable without them is a real bug.
 */
@Embeddable
public class HoldSeat {

    @Column(name = "seat_id", nullable = false, updatable = false)
    private Long seatId;

    /**
     * The parent's showtime, copied down. Redundant in Java, deliberate in SQL:
     * hold_seat carries showtime_id so the availability query can filter
     * without joining to the parent, and the composite foreign key on
     * (hold_id, showtime_id) is what stops the copy disagreeing with it.
     */
    @Column(name = "showtime_id", nullable = false, updatable = false)
    private Long showtimeId;

    /**
     * The price quoted for this seat, frozen at the moment of the claim. This
     * is what confirm charges, rather than working the price out again: once
     * occupancy is an input, recomputing at confirm can give a different
     * answer from the one the customer was shown.
     *
     * Per seat rather than once on the parent, even though a hold covers
     * exactly one tier today. That property is not a rule anyone wrote down,
     * it falls out of two unrelated ones: seats on a hold share a row, and
     * tier is assigned by row band. Storing it here means the schema stays
     * correct if either of those ever changes.
     */
    @Column(name = "price_minor", nullable = false, updatable = false)
    private int priceMinor;

    protected HoldSeat() {
        // Hibernate builds instances by reflection and needs a no-arg
        // constructor. protected keeps application code out of it.
    }

    /* Package-private: a HoldSeat is only ever built by SeatHold.claim. */
    static HoldSeat of(Long seatId, Long showtimeId, int priceMinor) {
        HoldSeat held = new HoldSeat();
        held.seatId = seatId;
        held.showtimeId = showtimeId;
        held.priceMinor = priceMinor;
        return held;
    }

    public Long getSeatId() {
        return seatId;
    }

    public int getPriceMinor() {
        return priceMinor;
    }

    /* Package-private. Nothing outside this package has a reason to read a
       child's copy of its parent's showtime; callers should ask the hold. */
    Long getShowtimeId() {
        return showtimeId;
    }

    /**
     * Identity is (seat, showtime), deliberately excluding the price, for the
     * same reason BookingSeat excludes it. Those two are what the table's
     * primary key is built on, so two elements agreeing on them are the same
     * row as far as Postgres is concerned. Including the price would let this
     * Set hold two elements the database then rejects as duplicates, turning a
     * bug that fails fast in memory into one that fails at flush time with a
     * constraint violation.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HoldSeat that)) return false;
        return Objects.equals(seatId, that.seatId)
                && Objects.equals(showtimeId, that.showtimeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seatId, showtimeId);
    }
}