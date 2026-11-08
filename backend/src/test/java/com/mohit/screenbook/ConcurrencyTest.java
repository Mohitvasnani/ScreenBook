package com.mohit.screenbook;

import com.mohit.screenbook.booking.BookingService;
import com.mohit.screenbook.common.HoldAlreadyConvertedException;
import com.mohit.screenbook.common.SeatsUnavailableException;
import com.mohit.screenbook.common.UnavailableSeat;
import com.mohit.screenbook.hold.HoldResponse;
import com.mohit.screenbook.hold.HoldService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.mohit.screenbook.booking.BookingResponse;
import com.mohit.screenbook.booking.IdempotentBookings;
import com.mohit.screenbook.common.IdempotencyKeyInProgressException;
import com.mohit.screenbook.common.IdempotencyKeyReusedException;


import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Deliberately NOT annotated @Transactional. A transaction on the test would
 * wrap everything, the service calls would join it instead of opening their
 * own, every thread would share one connection, and there would be no
 * concurrency left to test. The rollback at the end would then hide the fact
 * that nothing was proved.
 *
 * So these tests leave rows behind. That is fine: the container is thrown away
 * when the run ends, and every test picks fresh seats from the availability
 * view, which already excludes anything an earlier test claimed.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class ConcurrencyTest {

    /**
     * Eight, not eighty. Each contender holds a connection while it blocks on
     * the seat lock, and Hikari's default pool is ten. Ask for more contenders
     * than connections and the test hangs on the pool rather than proving
     * anything, which is itself the honest limitation of a pessimistic lock.
     */
    private static final int CONTENDERS = 8;

    @Autowired
    HoldService holds;

    @Autowired
    IdempotentBookings idempotent;

    @Autowired
    BookingService bookings;

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("eight simultaneous claims on one seat produce exactly one hold")
    void claimRace() throws Exception {
        Target target = someAvailableSeat();

        List<String> outcomes = runTogether(CONTENDERS, () -> {
            try {
                holds.claim(target.showtimeId(), List.of(target.seatId()), null);
                return "WON";
            } catch (SeatsUnavailableException e) {
                return "LOST";
            }
        });

        assertEquals(1, count(outcomes, "WON"),
                "exactly one claim should win");
        assertEquals(CONTENDERS - 1, count(outcomes, "LOST"),
                "every other claim should be told the seat is unavailable");
        assertEquals(1, liveHolds(target.showtimeId(), target.seatId()),
                "the database should contain exactly one live hold for that seat");
    }

    @Test
    @DisplayName("two simultaneous confirmations of one hold produce exactly one booking")
    void confirmRace() throws Exception {
        Target target = someAvailableSeat();
        HoldResponse hold = holds.claim(target.showtimeId(), List.of(target.seatId()), null);
        UUID holdId = hold.holdId();

        List<String> outcomes = runTogether(2, () -> {
            try {
                bookings.confirm(holdId, null, null);
                return "WON";
            } catch (HoldAlreadyConvertedException e) {
                return "LOST";
            }
        });

        assertEquals(1, count(outcomes, "WON"),
                "exactly one confirmation should win");
        assertEquals(1, count(outcomes, "LOST"),
                "the other should be told the hold is already converted");
        assertEquals(1, bookedSeats(target.showtimeId(), target.seatId()),
                "the database should contain exactly one booked seat");
    }

    /**
     * The reason sorted lock acquisition exists.
     *
     * Two requests want the same two seats in opposite orders. Locking in the
     * order asked, each would take one seat and then wait for the other, and
     * Postgres would break the cycle a second later by killing one transaction
     * with SQLSTATE 40P01, which Spring surfaces as CannotAcquireLockException.
     *
     * Claiming in ascending seat id order makes that cycle unformable: the
     * second request blocks on the lower seat before it holds anything, so it
     * is waiting on a transaction that is waiting on nobody. One request wins
     * outright and the other gets a clean 409.
     *
     * To see this test earn its keep, delete the .sorted() in HoldService.claim
     * and run it again. A concurrency test you have never watched fail is a
     * test you cannot trust.
     */
    @Test
    @DisplayName("opposite claim orders on the same seats do not deadlock")
    void noDeadlockOnOppositeOrders() throws Exception {
        List<Target> pair = availableSeatsInOneShowtime(2);
        long showtimeId = pair.getFirst().showtimeId();
        long low = pair.get(0).seatId();
        long high = pair.get(1).seatId();

        Callable<String> ascending = () -> attemptClaim(showtimeId, List.of(low, high));
        Callable<String> descending = () -> attemptClaim(showtimeId, List.of(high, low));

        List<String> outcomes = runTogether(List.of(ascending, descending));

        assertEquals(0, count(outcomes, "DEADLOCK"),
                "a deadlock means the locks were not taken in a consistent order");
        assertEquals(1, count(outcomes, "WON"),
                "exactly one request should get both seats");
        assertEquals(1, count(outcomes, "LOST"),
                "the other should be told the seats have gone, not deadlocked");
    }

    /**
     * All or nothing. One seat of the two is already held, so the claim fails,
     * and the seat that WAS free must still be free afterwards.
     *
     * This is the half of the contract that a 409 alone does not prove: the
     * request could have failed while still having written a row for the seat
     * it did get.
     */
    @Test
    @DisplayName("a claim that loses one seat claims none of them")
    void partialFailureClaimsNothing() {
        List<Target> pair = availableSeatsInOneShowtime(2);
        long showtimeId = pair.getFirst().showtimeId();
        long free = pair.get(0).seatId();
        long taken = pair.get(1).seatId();

        holds.claim(showtimeId, List.of(taken), null);

        SeatsUnavailableException thrown = assertThrows(SeatsUnavailableException.class,
                () -> holds.claim(showtimeId, List.of(free, taken), null));

        assertEquals(0, liveHolds(showtimeId, free),
                "the seat that was available must not have been claimed");

        @SuppressWarnings("unchecked")
        List<UnavailableSeat> lost =
                (List<UnavailableSeat>) thrown.getExtras().get("unavailableSeats");

        assertEquals(1, lost.size(), "only the taken seat should be reported");
        assertEquals(taken, lost.getFirst().seatId(),
                "the reported seat should be the one that was already held");
    }

    /**
     * The happy path, which is also the only thing that proves the element
     * collection actually writes rows. If HoldSeat were mapped wrongly this
     * would pass its assertions on the response and still leave hold_seat
     * empty, so the row count is checked directly.
     */
    @Test
    @DisplayName("a three seat claim writes one hold with three seats")
    void multiSeatClaimPersistsEverySeat() {
        List<Target> three = availableSeatsInOneShowtime(3);
        long showtimeId = three.getFirst().showtimeId();
        List<Long> seatIds = three.stream().map(Target::seatId).toList();

        HoldResponse hold = holds.claim(showtimeId, seatIds, null);

        assertEquals(3, hold.seats().size(), "the response should list three seats");
        assertEquals(
                hold.seats().stream().mapToInt(seat -> seat.priceMinor()).sum(),
                hold.totalMinor(),
                "the total should be the sum of the seat prices");
        assertEquals(3, holdSeatRows(hold.holdId()),
                "hold_seat should contain one row per seat");
    }
    /**
     * The point of the whole mechanism: a client that retries because it never
     * saw the first response gets the original booking back, not a 409.
     */
    @Test
    @DisplayName("confirming twice with one idempotency key returns the same booking")
    void idempotentReplay() {
        Target target = someAvailableSeat();
        HoldResponse hold = holds.claim(target.showtimeId(), List.of(target.seatId()), null);
        String key = "replay-" + UUID.randomUUID();

        BookingResponse first = idempotent.confirm(hold.holdId(), key, null, null);
        BookingResponse second = idempotent.confirm(hold.holdId(), key, null, null);

        assertEquals(first.bookingReference(), second.bookingReference(),
                "the retry should return the original booking");
        assertEquals(1, bookedSeats(target.showtimeId(), target.seatId()),
                "the retry must not create a second booking");
    }

    /**
     * Without the key, the second confirmation is a 409. That is correct and
     * unhelpful, and it is the behaviour the key exists to improve on, so it is
     * worth pinning down next to the replay test.
     */
    @Test
    @DisplayName("confirming twice without a key is still a conflict")
    void withoutKeyStillConflicts() {
        Target target = someAvailableSeat();
        HoldResponse hold = holds.claim(target.showtimeId(), List.of(target.seatId()), null);

        idempotent.confirm(hold.holdId(), null, null, null);

        assertThrows(HoldAlreadyConvertedException.class,
                () -> idempotent.confirm(hold.holdId(), null, null, null));
    }

    /**
     * A key reused for a different hold is a client bug, so it is reported
     * rather than quietly replaying a booking the caller did not ask about.
     */
    @Test
    @DisplayName("reusing a key for a different hold is rejected")
    void keyReuseRejected() {
        List<Target> pair = availableSeatsInOneShowtime(2);
        long showtimeId = pair.getFirst().showtimeId();

        HoldResponse first = holds.claim(showtimeId, List.of(pair.get(0).seatId()), null);
        HoldResponse second = holds.claim(showtimeId, List.of(pair.get(1).seatId()), null);

        String key = "reuse-" + UUID.randomUUID();
        idempotent.confirm(first.holdId(), key, null, null);

        assertThrows(IdempotencyKeyReusedException.class,
                () -> idempotent.confirm(second.holdId(), key, null, null));
    }

    /**
     * The ordering test. Two retries arriving together must not both do the
     * work: the key is claimed before the booking runs, so one proceeds and the
     * other either replays or is told the first is in flight. What must never
     * happen is two different booking references.
     *
     * Both outcomes are legitimate and which one you get depends on timing, so
     * the assertion is on what cannot happen rather than on which branch won.
     */
    @Test
    @DisplayName("two simultaneous confirmations with one key never produce two bookings")
    void idempotentConfirmRace() throws Exception {
        Target target = someAvailableSeat();
        HoldResponse hold = holds.claim(target.showtimeId(), List.of(target.seatId()), null);
        String key = "race-" + UUID.randomUUID();

        List<String> outcomes = runTogether(2, () -> {
            try {
                return idempotent.confirm(hold.holdId(), key, null, null).bookingReference();
            } catch (IdempotencyKeyInProgressException e) {
                return "IN_PROGRESS";
            }
        });

        long distinctReferences = outcomes.stream()
                .filter(outcome -> outcome.startsWith("SB-"))
                .distinct()
                .count();

        assertTrue(distinctReferences <= 1,
                "two different booking references means the key did not serialise them: " + outcomes);
        assertEquals(1, bookedSeats(target.showtimeId(), target.seatId()),
                "the database should contain exactly one booked seat");
    }

    /* ------------------------------------------------------------- plumbing */

    private String attemptClaim(long showtimeId, List<Long> seatIds) {
        try {
            holds.claim(showtimeId, seatIds, null);
            return "WON";
        } catch (SeatsUnavailableException e) {
            return "LOST";
        } catch (CannotAcquireLockException e) {
            /* Caught and named rather than allowed to propagate, so a failure
               reads as "deadlocked" instead of as a stack trace. */
            return "DEADLOCK";
        }
    }

    /**
     * Runs the same task on n threads released at the same instant.
     *
     * The barrier is the whole point. Without it the threads start whenever the
     * pool gets round to them, which can be far enough apart that they never
     * overlap, and then the test passes without having tested anything.
     */
    private <T> List<T> runTogether(int n, Callable<T> task) throws Exception {
        return runTogether(Collections.nCopies(n, task));
    }

    /** Different tasks on one start line, for races between unlike requests. */
    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(tasks.size());
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                /* get() rethrows anything the task threw other than the ones
                   caught above, so an unexpected failure fails the test loudly
                   instead of being counted as a loss. */
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
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

    /**
     * Several available seats in the SAME showtime, ordered by seat id, so a
     * test can ask for them in ascending or descending order deliberately.
     * The subquery picks a showtime that has enough of them rather than
     * assuming the first one does.
     */
    /**
     * N consecutive available seats in one row, which is what the adjacency
     * rule now requires of any multi-seat claim.
     *
     * The CTE is the standard "gaps and islands" trick: within a row ordered by
     * seat number, seat_number minus row_number() is constant across a run of
     * consecutive seats and changes at every gap, so grouping by it groups by
     * run. HAVING then keeps only runs long enough to use.
     *
     * Every caller gets a fresh one. Earlier tests hold seats, those drop out
     * of the availability view, so each test lands on a different run rather
     * than fighting over one.
     */
    private List<Target> availableSeatsInOneShowtime(int howMany) {
        List<Long> seatIds = jdbc.sql("""
                        WITH runs AS (
                            SELECT showtime_id,
                                   row_label,
                                   seat_id,
                                   seat_number,
                                   seat_number - row_number() OVER (
                                       PARTITION BY showtime_id, row_label
                                       ORDER BY seat_number) AS run_key
                            FROM seat_availability
                            WHERE status = 'AVAILABLE')
                        SELECT array_agg(seat_id ORDER BY seat_number) AS seat_ids
                        FROM runs
                        GROUP BY showtime_id, row_label, run_key
                        HAVING count(*) >= :howMany
                        ORDER BY min(showtime_id), min(row_label), min(seat_number)
                        LIMIT 1
                        """)
                .param("howMany", howMany)
                .query((rs, rowNum) ->
                        Arrays.asList((Long[]) rs.getArray("seat_ids").getArray()))
                .single();

        long showtimeId = showtimeOf(seatIds.getFirst());
        return seatIds.stream()
                .limit(howMany)
                .map(seatId -> new Target(showtimeId, seatId))
                .toList();
    }

    /** The run query groups by showtime, so every seat in it shares this one. */
    private long showtimeOf(long seatId) {
        return jdbc.sql("""
                        SELECT showtime_id
                        FROM seat_availability
                        WHERE seat_id = :seatId AND status = 'AVAILABLE'
                        ORDER BY showtime_id
                        LIMIT 1
                        """)
                .param("seatId", seatId)
                .query(Long.class)
                .single();
    }

    /** Live holds covering one seat. Joins the child, since the seat lives there now. */
    private int liveHolds(long showtimeId, long seatId) {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM seat_hold h
                        JOIN hold_seat hs ON hs.hold_id = h.id
                        WHERE hs.showtime_id = :showtimeId
                          AND hs.seat_id     = :seatId
                          AND h.booked_at    IS NULL
                          AND h.released_at  IS NULL
                          AND h.expires_at   > now()
                        """)
                .param("showtimeId", showtimeId)
                .param("seatId", seatId)
                .query(Integer.class).single();
    }

    private int bookedSeats(long showtimeId, long seatId) {
        return jdbc.sql("""
                        SELECT count(*) FROM booking_seat
                        WHERE showtime_id = :showtimeId AND seat_id = :seatId
                        """)
                .param("showtimeId", showtimeId)
                .param("seatId", seatId)
                .query(Integer.class).single();
    }

    private int holdSeatRows(UUID holdId) {
        return jdbc.sql("SELECT count(*) FROM hold_seat WHERE hold_id = :holdId")
                .param("holdId", holdId)
                .query(Integer.class).single();
    }

    private static long count(List<String> outcomes, String outcome) {
        return outcomes.stream().filter(outcome::equals).count();
    }

}
