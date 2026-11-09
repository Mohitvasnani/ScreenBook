package com.mohit.screenbook.hold;

import com.mohit.screenbook.pricing.PriceBreakdown;
import com.mohit.screenbook.pricing.PriceRule;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.UuidGenerator;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "seat_hold")
public class SeatHold {

    /**
     * RELEASED is internal. The API reports it as EXPIRED, because from the
     * client's point of view the outcome is identical: the seats are free and
     * you start again.
     */
    public enum State { ACTIVE, EXPIRED, CONVERTED, RELEASED }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "showtime_id", nullable = false, updatable = false)
    private Long showtimeId;

    /**
     * The seat's listed price for this hold's tier, before any demand rule.
     * One value for the whole hold, because a hold covers one tier, and it is
     * what the breakdown counts up from: base plus every rule line equals the
     * per-seat price on the children.
     */
    @Column(name = "base_price_minor", nullable = false, updatable = false)
    private int basePriceMinor;

    /**
     * The seats this hold covers, one to six of them.
     *
     * @ElementCollection stores a collection of value types in a table of
     * their own; @CollectionTable names that table and the column pointing
     * back here. A Set rather than a List because the table's primary key is
     * (hold_id, seat_id), so a seat cannot appear twice, and a List without an
     * @OrderColumn would be a Hibernate "bag" with no guaranteed order anyway.
     *
     * Element collections are rewritten wholesale when they change: Hibernate
     * deletes every row for the parent and reinserts. That is a real trap in
     * general and harmless here, because a hold's seats are fixed at the
     * moment of claim and never change, so the expensive path never runs.
     *
     * LAZY by default, which is correct. With spring.jpa.open-in-view=false
     * this has to be read inside the transaction that loaded the hold. Making
     * it EAGER would drag the seat rows on every findById.
     */
    @ElementCollection
    @CollectionTable(
            name = "hold_seat",
            joinColumns = @JoinColumn(name = "hold_id")
    )
    private Set<HoldSeat> seats = new LinkedHashSet<>();

    /**
     * Why the price is what it is, frozen with it. One row per rule including
     * the ones that landed in a zero band, so a hold taken before a rule
     * existed stays distinguishable from one where the rule did not fire.
     *
     * A Set with no ordering guarantee, like seats. getPriceRules sorts by
     * sortOrder, so display order is decided in one place rather than relying
     * on whatever order Postgres returns.
     */
    @ElementCollection
    @CollectionTable(
            name = "hold_price_rule",
            joinColumns = @JoinColumn(name = "hold_id")
    )
    private Set<HoldPriceRule> priceRules = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "booked_at")
    private Instant bookedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    /**
     * One version for the whole hold, however many seats it covers. The
     * conversion's UPDATE carries AND version = ?, so a second writer affects
     * zero rows and loses. Keeping this on the parent is the reason the seats
     * are an owned collection and not the inverse side of a @OneToMany: with
     * mappedBy, changes to the children would not touch this column and the
     * lock would be weaker than it looks.
     */
    @Version
    private long version;

    protected SeatHold() {
    }

    /**
     * The only way to create a hold.
     *
     * The breakdown arrives already computed, and this is where it stops being
     * a calculation and becomes a record. Every seat gets the same adjusted
     * price because a hold covers exactly one tier: seats on a hold share a
     * row, and tier is assigned by row band. It is still written per seat, so
     * the schema stays correct if either of those rules ever changes.
     *
     * The empty check is a backstop, not the error path: CreateHoldRequest's
     * @Size(min = 1, max = 6) rejects it over HTTP long before this runs, so
     * this firing means a bug in a caller rather than a user mistake. A hold
     * with no seats is nonsense and should be unconstructable.
     */
    public static SeatHold claim(Long showtimeId, Collection<Long> seatIds,
                                 PriceBreakdown price, Instant now, Duration ttl) {
        if (seatIds == null || seatIds.isEmpty()) {
            throw new IllegalArgumentException("A hold must cover at least one seat");
        }

        SeatHold hold = new SeatHold();
        hold.showtimeId = showtimeId;
        hold.basePriceMinor = price.basePriceMinor();
        hold.createdAt = now;
        hold.expiresAt = now.plus(ttl);

        for (Long seatId : seatIds) {
            /* The showtime is copied into each child so the row satisfies the
               composite foreign key. The parent is the only thing that knows
               it, which is why the children are built here and nowhere else. */
            hold.seats.add(HoldSeat.of(seatId, showtimeId, price.adjustedPriceMinor()));
        }

        for (PriceRule rule : price.rules()) {
            hold.priceRules.add(HoldPriceRule.of(
                    rule.code(), rule.label(),
                    rule.basisPoints(), rule.adjustmentMinor(), rule.sortOrder()));
        }

        return hold;
    }

    /**
     * The whole state machine, in one expression. Nothing stores the state;
     * it is worked out from three timestamps, which is the same rule the
     * availability query applies in SQL. One state for the hold, not one per
     * seat: a hold expires whole and converts whole.
     */
    public State stateAt(Instant now) {
        if (bookedAt != null) return State.CONVERTED;
        if (releasedAt != null) return State.RELEASED;
        if (!expiresAt.isAfter(now)) return State.EXPIRED;
        return State.ACTIVE;
    }

    /**
     * Guards are a backstop, not the error path. The service checks the state
     * first and throws the exception that maps to the right problem type, so
     * this firing means a bug, not a user mistake.
     */
    public void markBooked(Instant now) {
        State state = stateAt(now);
        if (state != State.ACTIVE) {
            throw new IllegalStateException("Cannot book a hold in state " + state);
        }
        this.bookedAt = now;
    }

    public void release(Instant now) {
        State state = stateAt(now);
        if (state != State.ACTIVE) {
            throw new IllegalStateException("Cannot release a hold in state " + state);
        }
        this.releasedAt = now;
    }

    /** Never negative, so the client's countdown cannot start below zero. */
    public long remainingSeconds(Instant now) {
        return Math.max(0, Duration.between(now, expiresAt).toSeconds());
    }

    public UUID getId() { return id; }
    public Long getShowtimeId() { return showtimeId; }
    public int getBasePriceMinor() { return basePriceMinor; }
    public Instant getExpiresAt() { return expiresAt; }
    public long getVersion() { return version; }

    /**
     * Unmodifiable, so nothing can add a seat to a hold after it was claimed.
     * No ordering guarantee: the rows come back in whatever order Postgres
     * returns them. Anything displaying seats sorts them by row and number
     * from the seat table, because seat ids are not guaranteed to follow
     * seating order.
     */
    public Set<HoldSeat> getSeats() {
        return Set.copyOf(seats);
    }

    /** Ordered for display, so no caller has to know about sortOrder. */
    public List<HoldPriceRule> getPriceRules() {
        return priceRules.stream()
                .sorted(Comparator.comparingInt(HoldPriceRule::getSortOrder))
                .toList();
    }

    /**
     * Derived, never stored. The same rule as Booking.totalMinor: a stored
     * total is a second place for the truth to live and a chance for it to
     * drift from the lines it summarises.
     */
    public int totalMinor() {
        return seats.stream().mapToInt(HoldSeat::getPriceMinor).sum();
    }
}