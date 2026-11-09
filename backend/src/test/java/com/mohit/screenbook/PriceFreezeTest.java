package com.mohit.screenbook;

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The promise this wave exists to make: the price quoted when seats are held
 * is the price charged when they are booked.
 *
 * Not @Transactional, for the same reason the other service-level tests are
 * not: these call services that open their own transactions, and a rollback at
 * the end would hide whether anything was written.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class PriceFreezeTest {

    /** 50 rupees in paise. Large enough that no rounding could hide it. */
    private static final int BUMP = 5_000;

    @Autowired
    HoldService holds;

    @Autowired
    BookingService bookings;

    @Autowired
    JdbcClient jdbc;

    private record Seat(long showtimeId, long seatId, String hallClass, String tier) {
    }

    /**
     * Holds a seat, then changes the listed price underneath it, then confirms.
     *
     * Moving the listed price rather than the occupancy is deliberate. Pushing
     * a 120 seat house past the 50 percent band would need sixty seats across
     * a dozen holds, and mocking the clock would move the lead-time band for
     * every other test sharing this context. One UPDATE changes the live price
     * with no ambiguity about what changed.
     *
     * It is also the realistic version: prices do get edited, and before this
     * wave confirm re-read the view, so an edit between hold and confirm
     * silently charged the new number.
     */
    @Test
    @DisplayName("the price quoted at hold time is the price charged at booking")
    void quotedPriceIsTheChargedPrice() {
        Seat seat = someBookableSeat();

        HoldResponse held = holds.claim(seat.showtimeId(), List.of(seat.seatId()), null);
        int quoted = held.totalMinor();
        int listedWhenHeld = listedPriceOf(seat.seatId(), seat.showtimeId());

        try {
            bumpListedPrice(seat.hallClass(), seat.tier(), BUMP);

            /* Prove the change actually landed before asserting anything about
               the freeze. Without this the test passes even if nothing was
               ever frozen, because the two numbers would agree by accident. */
            assertEquals(listedWhenHeld + BUMP,
                    listedPriceOf(seat.seatId(), seat.showtimeId()),
                    "the listed price should have moved, or this test proves nothing");

            BookingResponse booked = bookings.confirm(held.holdId(), null, null);

            assertEquals(quoted, booked.totalPaidMinor(),
                    "the booking must charge what the hold quoted, not today's price");
        } finally {
            /* The container is per run but the Spring context is shared across
               test classes, so leaving the seed prices edited would make a
               later class price against numbers nobody chose. */
            bumpListedPrice(seat.hallClass(), seat.tier(), -BUMP);
        }
    }

    /**
     * seat_availability joins the price table with an inner join, so a hall
     * class with a missing tier price does not error, it makes those seats
     * vanish from the seat map. The primary key cannot prevent that, because it
     * only stops duplicates.
     *
     * This is the cheapest possible guard and it fails at build time rather
     * than as an empty row in production.
     */
    @Test
    @DisplayName("every hall class has a price for every tier")
    void everyHallClassAndTierHasAPrice() {
        List<String> missing = jdbc.sql("""
                        SELECT hc.code || '/' || t.code AS combination
                        FROM hall_class hc
                        CROSS JOIN seat_tier t
                        LEFT JOIN hall_class_tier_price p
                               ON p.hall_class = hc.code
                              AND p.tier       = t.code
                        WHERE p.price_minor IS NULL
                        ORDER BY hc.sort_order, t.sort_order
                        """)
                .query(String.class)
                .list();

        assertTrue(missing.isEmpty(),
                "these hall class and tier combinations have no price, so their "
                        + "seats disappear from the seat map: " + missing);
    }

    /**
     * showtime_occupancy counts with correlated subqueries for speed;
     * seat_availability decides the same thing with a CASE per seat. Both read
     * the same two child tables with the same liveness test, so they agree
     * today, and nothing but this test stops them drifting apart.
     *
     * The price depends on the first and the customer looks at the second, so a
     * disagreement means charging against an occupancy nobody can see.
     */
    @Test
    @DisplayName("showtime_occupancy agrees with the seat map it is derived from")
    void occupancyAgreesWithTheSeatMap() {
        /* Make sure something is actually taken. Against an untouched house
           every count is zero and the comparison would pass vacuously. */
        Seat seat = someBookableSeat();
        holds.claim(seat.showtimeId(), List.of(seat.seatId()), null);

        int busyShowtimes = jdbc.sql(
                        "SELECT count(*) FROM showtime_occupancy WHERE seats_taken > 0")
                .query(Integer.class)
                .single();
        assertTrue(busyShowtimes > 0, "nothing is held, so this test would prove nothing");

        List<String> disagreements = jdbc.sql("""
                        SELECT occ.showtime_id
                               || ': view says ' || sa.taken || '/' || sa.total
                               || ', occupancy says ' || occ.seats_taken
                               || '/' || occ.seats_total          AS mismatch
                        FROM showtime_occupancy occ
                        JOIN (SELECT showtime_id,
                                     count(*)                                     AS total,
                                     count(*) FILTER (WHERE status <> 'AVAILABLE') AS taken
                              FROM seat_availability
                              GROUP BY showtime_id) sa
                          ON sa.showtime_id = occ.showtime_id
                        WHERE sa.total <> occ.seats_total
                           OR sa.taken <> occ.seats_taken
                        ORDER BY occ.showtime_id
                        """)
                .query(String.class)
                .list();

        assertTrue(disagreements.isEmpty(),
                "occupancy and the seat map must count the same seats: " + disagreements);
    }

    // ------------------------------------------------------------- helpers

    /**
     * An available seat in a screening that has not started, plus the two
     * things that decide its listed price. Joined out to seat and screen,
     * because seat_availability carries the hall class but not the screen.
     */
    private Seat someBookableSeat() {
        return jdbc.sql("""
                        SELECT sa.showtime_id, sa.seat_id, sa.tier, sa.hall_class
                        FROM seat_availability sa
                        JOIN showtime st ON st.id = sa.showtime_id
                        WHERE sa.status = 'AVAILABLE'
                          AND st.starts_at > now()
                        LIMIT 1
                        """)
                .query((rs, rowNum) -> new Seat(
                        rs.getLong("showtime_id"),
                        rs.getLong("seat_id"),
                        rs.getString("hall_class"),
                        rs.getString("tier")))
                .single();
    }

    private int listedPriceOf(long seatId, long showtimeId) {
        return jdbc.sql("""
                        SELECT base_price_minor
                        FROM seat_availability
                        WHERE showtime_id = :showtimeId AND seat_id = :seatId
                        """)
                .param("showtimeId", showtimeId)
                .param("seatId", seatId)
                .query(Integer.class)
                .single();
    }

    /**
     * Asserts it actually changed a row. An UPDATE that matches nothing is not
     * an error in SQL, it is a no-op returning zero, so without this check a
     * wrong hall class or tier surfaces later as a confusing failure about
     * prices rather than here as the real cause.
     */
    private void bumpListedPrice(String hallClass, String tier, int byMinor) {
        int updated = jdbc.sql("""
                        UPDATE hall_class_tier_price
                           SET price_minor = price_minor + :byMinor
                         WHERE hall_class = :hallClass AND tier = :tier
                        """)
                .param("byMinor", byMinor)
                .param("hallClass", hallClass)
                .param("tier", tier)
                .update();

        assertEquals(1, updated,
                "no price row for hall class %s and tier %s".formatted(hallClass, tier));
    }
}