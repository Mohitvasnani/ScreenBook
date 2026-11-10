package com.mohit.screenbook;

import com.mohit.screenbook.catalogue.SeedProperties;
import com.mohit.screenbook.catalogue.ShowtimeSeeder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seeder does not run at startup in tests, so these call it directly.
 *
 * @Transactional here, unlike every other integration test in this project.
 * These do not go through a service that opens its own transactions, so the
 * seeder joins the test's and the roughly 6,700 rows it writes roll back at
 * the end. Without that, the first test to run would leave a million rows in
 * seat_availability for every test class after it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShowtimeSeederTest {

    @Autowired
    ShowtimeSeeder seeder;

    @Autowired
    SeedProperties properties;

    @Autowired
    JdbcClient jdbc;

    /**
     * The property the whole design rests on. A migration cannot be run twice;
     * this has to be safe to run on every boot, forever.
     */
    @Test
    @DisplayName("a second run creates nothing")
    void isIdempotent() {
        ShowtimeSeeder.Result first = seeder.seed();
        ShowtimeSeeder.Result second = seeder.seed();

        assertTrue(first.inserted() > 0,
                "the first run seeded nothing, so the second proves nothing");
        assertEquals(0, second.inserted(),
                "the second run created screenings that already existed");
    }

    /**
     * A screening in the past is a row nobody can book and that no endpoint
     * lists. V13 avoided them with the same WHERE clause and for the same
     * reason: they also make tests nondeterministic, because a test that
     * discovers a screening from the availability view can land on one that is
     * already rejected.
     */
    @Test
    @DisplayName("nothing is seeded into the past")
    void seedsOnlyTheFuture() {
        long before = highestShowtimeId();

        seeder.seed();

        long inThePast = jdbc.sql("""
                        SELECT count(*) FROM showtime
                        WHERE id > :before AND starts_at <= now()
                        """)
                .param("before", before)
                .query(Long.class)
                .single();

        assertEquals(0, inThePast, "the seeder created screenings that have already started");
    }

    /**
     * The one that would fail if the zone were hardcoded.
     *
     * Every seeded screening, read back in its own cinema's zone, must land in
     * the 20 minute window that starts at one of the configured slots. The
     * window is 20 rather than exact because of the per-screen stagger, which
     * is at most 15 minutes.
     *
     * With a single global Asia/Kolkata, every UK screening would read as
     * 16:00 local for a 10:30 slot and the count would be every British row.
     * Scoped to rows this test created, because V13's Cinepolis screenings
     * predate the slot list and would fail it honestly.
     */
    @Test
    @DisplayName("every screening lands on a slot in its own cinema's local time")
    void usesEachCinemasOwnClock() {
        long before = highestShowtimeId();

        seeder.seed();

        long offSchedule = jdbc.sql("""
                        SELECT count(*)
                        FROM showtime st
                        JOIN screen sc ON sc.id = st.screen_id
                        JOIN cinema ci ON ci.id = sc.cinema_id
                        WHERE st.id > :before
                          AND NOT EXISTS (
                              SELECT 1
                              FROM unnest(CAST(string_to_array(:slots, ',') AS time[])) AS s(t)
                              WHERE CAST(st.starts_at AT TIME ZONE ci.timezone AS time) >= s.t
                                AND CAST(st.starts_at AT TIME ZONE ci.timezone AS time)
                                      < s.t + interval '20 minutes'
                          )
                        """)
                .param("before", before)
                .param("slots", slotList())
                .query(Long.class)
                .single();

        assertEquals(0, offSchedule,
                "screenings landed at a local time that is not one of the configured slots");
    }

    private long highestShowtimeId() {
        return jdbc.sql("SELECT coalesce(max(id), 0) FROM showtime")
                .query(Long.class)
                .single();
    }

    private String slotList() {
        return properties.slots().stream()
                .map(LocalTime::toString)
                .collect(Collectors.joining(","));
    }
}