package com.mohit.screenbook.booking;

import com.mohit.screenbook.common.Emails;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A booking never changes once written, so every column is updatable = false
 * and there is no @Version. Cancellation is out of scope, and if it arrives it
 * is a new row, not an edit.
 */
@Entity
@Table(name = "booking")
public class Booking {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Column(name = "hold_id", nullable = false, updatable = false)
    private UUID holdId;

    @Column(name = "showtime_id", nullable = false, updatable = false)
    private Long showtimeId;

    /**
     * Null for a guest booking. Set when someone was signed in at the moment
     * they confirmed, which is what "my bookings" is built on.
     */
    @Column(name = "user_id", updatable = false)
    private UUID userId;

    /**
     * Null unless supplied. Only purpose is sending a confirmation; it is
     * never used to decide who a booking belongs to, because an unverified
     * address would let anyone claim someone else's history by signing up with
     * their email.
     */
    @Column(updatable = false)
    private String email;

    @ElementCollection
    @CollectionTable(
            name = "booking_seat",
            joinColumns = @JoinColumn(name = "booking_id")
    )
    private Set<BookingSeat> seats = new LinkedHashSet<>();

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "booked_at", nullable = false, updatable = false)
    private Instant bookedAt;

    protected Booking() {
    }

    /**
     * Takes plain values rather than a SeatHold, so this package does not
     * depend on the hold package.
     *
     * Prices arrive as a map from seat id to price so a seat cannot be listed
     * twice by construction, and so the caller has to have resolved a price
     * for every seat before it gets here.
     */
    public static Booking of(UUID holdId, Long showtimeId,
                             Map<Long, Integer> pricesBySeatId, String currency,
                             String reference, UUID userId, String email, Instant now) {
        if (pricesBySeatId == null || pricesBySeatId.isEmpty()) {
            throw new IllegalArgumentException("A booking must cover at least one seat");
        }

        Booking booking = new Booking();
        booking.holdId = holdId;
        booking.showtimeId = showtimeId;
        booking.currency = currency;
        booking.reference = reference;
        booking.userId = userId;
        /* Normalised here rather than trusted from the caller, because the
           CHECK on the column is what finally enforces it and the entity is
           the last thing to touch the value before the INSERT. */
        booking.email = Emails.normalise(email);
        booking.bookedAt = now;
        pricesBySeatId.forEach((seatId, priceMinor) ->
                booking.seats.add(BookingSeat.of(seatId, showtimeId, priceMinor)));
        return booking;
    }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public UUID getHoldId() { return holdId; }
    public Long getShowtimeId() { return showtimeId; }
    public UUID getUserId() { return userId; }
    public String getEmail() { return email; }
    public String getCurrency() { return currency; }
    public Instant getBookedAt() { return bookedAt; }

    /** Unmodifiable: a booking's seats are fixed the moment it is written. */
    public Set<BookingSeat> getSeats() {
        return Set.copyOf(seats);
    }

    /**
     * Derived, never stored. A stored total is a second place for the truth to
     * live and a chance for it to drift from the lines it summarises.
     */
    public int totalMinor() {
        return seats.stream().mapToInt(BookingSeat::getPricePaidMinor).sum();
    }
}
