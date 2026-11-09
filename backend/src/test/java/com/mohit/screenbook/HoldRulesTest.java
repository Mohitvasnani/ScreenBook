package com.mohit.screenbook;

import com.mohit.screenbook.common.ValidationFailedException;
import com.mohit.screenbook.hold.HoldResponse;
import com.mohit.screenbook.hold.HoldService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Arrays;
import java.util.List;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The rules about WHICH seats may be held together, as opposed to the
 * concurrency rules about who gets them. Same context configuration as
 * ConcurrencyTest, so Spring reuses the one it already started.
 *
 * Not @Transactional, for the same reason: these call the service, which opens
 * its own transactions. Rows are left behind and the container is thrown away.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class HoldRulesTest {

    @Autowired
    HoldService holds;

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("three seats in a row are fine")
    void runOfThreeIsFine() {
        Run run = fourInARow();

        HoldResponse hold = holds.claim(run.showtimeId(), run.seatIds().subList(0, 3), null);

        assertEquals(3, hold.seats().size());
    }

    /**
     * Seats 3 and 4 have a walking aisle between them. The rule counts them as
     * neighbours on purpose: the alternative confines any group of four or more
     * to the six-wide centre block.
     */
    @Test
    @DisplayName("an aisle does not break a run")
    void acrossAnAisleIsStillAdjacent() {
        Run run = fourInARow();

        HoldResponse hold = holds.claim(
                run.showtimeId(), List.of(run.seatIds().get(2), run.seatIds().get(3)), null);

        assertEquals(2, hold.seats().size());
    }

    @Test
    @DisplayName("a gap between seats is rejected")
    void gapIsRejected() {
        Run run = fourInARow();

        assertThrows(ValidationFailedException.class, () ->
                holds.claim(run.showtimeId(),
                        List.of(run.seatIds().get(0), run.seatIds().get(2)), null));
    }

    @Test
    @DisplayName("seats in different rows are rejected")
    void differentRowsRejected() {
        Run run = fourInARow();
        long elsewhere = seatInAnotherRow(run.showtimeId(), run.rowLabel());

        assertThrows(ValidationFailedException.class, () ->
                holds.claim(run.showtimeId(), List.of(run.seatIds().getFirst(), elsewhere), null));
    }

    @Test
    @DisplayName("the same seat twice is rejected")
    void duplicateRejected() {
        Run run = fourInARow();
        long seatId = run.seatIds().getFirst();

        assertThrows(ValidationFailedException.class, () ->
                holds.claim(run.showtimeId(), List.of(seatId, seatId), null));
    }

    /* ------------------------------------------------------------- fixtures */

    private record Run(long showtimeId, String rowLabel, List<Long> seatIds) {
    }

    /**
     * Seats 1 to 4 of some row where all four are free, which spans the first
     * aisle. The CTE picks the row; the outer query collects its seat ids in
     * seat order with array_agg, so the caller gets them already sorted.
     *
     * Every test calls this fresh. Earlier tests hold seats, and the
     * availability view excludes those, so each test naturally lands on a
     * different row rather than fighting over one.
     */
    private Run fourInARow() {
        return jdbc.sql("""
                                                WITH candidate AS (
                                                                SELECT sa.showtime_id, sa.row_label
                                                                FROM seat_availability sa
                                                                JOIN showtime st ON st.id = sa.showtime_id
                                                                WHERE sa.status = 'AVAILABLE'
                                                                  AND sa.seat_number BETWEEN 1 AND 4
                                                                  AND st.starts_at > now()
                                                                GROUP BY sa.showtime_id, sa.row_label
                                                                HAVING count(*) = 4
                                                                ORDER BY sa.showtime_id, sa.row_label
                                                                LIMIT 1)
                        SELECT sa.showtime_id,
                               sa.row_label,
                               array_agg(sa.seat_id ORDER BY sa.seat_number) AS seat_ids
                        FROM seat_availability sa
                        JOIN candidate c
                          ON c.showtime_id = sa.showtime_id
                         AND c.row_label   = sa.row_label
                        WHERE sa.seat_number BETWEEN 1 AND 4
                        GROUP BY sa.showtime_id, sa.row_label
                        """)
                .query((rs, rowNum) -> new Run(
                        rs.getLong("showtime_id"),
                        rs.getString("row_label"),
                        Arrays.asList((Long[]) rs.getArray("seat_ids").getArray())))
                .single();
    }

    private long seatInAnotherRow(long showtimeId, String notThisRow) {
        return jdbc.sql("""
                        SELECT seat_id
                        FROM seat_availability
                        WHERE showtime_id = :showtimeId
                          AND status      = 'AVAILABLE'
                          AND row_label  <> :notThisRow
                        ORDER BY seat_id
                        LIMIT 1
                        """)
                .param("showtimeId", showtimeId)
                .param("notThisRow", notThisRow)
                .query(Long.class)
                .single();
    }
}