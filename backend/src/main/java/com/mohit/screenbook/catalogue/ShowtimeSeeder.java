package com.mohit.screenbook.catalogue;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.stream.Collectors;

/**
 * Keeps the demo stocked with screenings.
 *
 * V13 seeded a week and stopped, because a migration runs once. Flyway records
 * it as applied and never revisits it, so its data is a photograph of the
 * moment it ran and the demo runs dry a week after deploy. This replaces that
 * with a statement of the target state:
 *
 *   every screen has screenings for the next N days, at times local to its
 *   cinema, and none in the past
 *
 * and converges to it on every startup. Idempotency is not a feature added on
 * top, it falls out of describing a state rather than an action. The second run
 * finds the state already true and inserts nothing.
 *
 * A bean in its own right, separate from the runner that calls it, so the test
 * can invoke it directly while startup seeding stays switched off for the rest
 * of the suite.
 */
@Component
public class ShowtimeSeeder {

    /* One statement, not a loop. 240 screens over 7 days is roughly 6,700
       screenings, and a Java loop would be 6,700 round trips reconstructing in
       application code what Postgres already knows how to do: what today's
       date is in Asia/Kolkata, and what a wall clock reading in that zone is
       as an instant.

       AT TIME ZONE takes ci.timezone, a column, not a literal. That is the
       whole point of V17 putting the zone on the cinema. A Jaipur screening is
       scheduled on Jaipur's clock and a Liverpool one on Liverpool's, and
       neither reads a server default or a global config value.

       The film subquery is a ring. row_number gives each film a 0-based
       position and count(*) OVER () gives the size, so "the next film" is
       ring_pos mod total, a join rather than a lookup. It takes whatever films
       exist, so when TMDB lands and there are forty of them this statement
       spreads them without a change. With no films at all the subquery has no
       rows, so the join produces nothing and the modulo is never evaluated.
       No division by zero to guard.

       The stagger exists so that not every screen in the country starts at
       exactly 10:30. It is a function of the screen id, so it is stable: the
       same screen gets the same offset on every run, which is what lets
       ON CONFLICT recognise a screening it created yesterday.

       starts_at > now() is why this is a rolling window rather than a fixed
       one. Today's slots that have already passed are skipped, tomorrow's
       startup adds day N. ON CONFLICT then makes re-running free.

       CAST rather than the :: operator throughout. Both work, and Spring's
       named parameter parser does handle ::, but a string that already uses :
       for parameters is a bad place to also use : as a cast. */
    private static final String INSERT = """
            INSERT INTO showtime (film_id, screen_id, starts_at)
            SELECT f.id, c.screen_id, c.starts_at
            FROM (
                    SELECT sc.id AS screen_id,

                           (CAST(now() AT TIME ZONE ci.timezone AS date)
                                + d.n
                                + slot.time_of_day
                                + (sc.id % 4) * interval '5 minutes')
                             AT TIME ZONE ci.timezone        AS starts_at,

                           sc.id + d.n + slot.idx            AS ring_pos

                    FROM screen sc
                    JOIN cinema ci ON ci.id = sc.cinema_id
                    CROSS JOIN generate_series(0, :lastDay) AS d(n)
                    CROSS JOIN unnest(CAST(string_to_array(:slots, ',') AS time[]))
                               WITH ORDINALITY AS slot(time_of_day, idx)
                 ) c
            JOIN (
                    SELECT id,
                           row_number() OVER (ORDER BY id) - 1 AS n,
                           count(*)     OVER ()                AS total
                    FROM film
                 ) f ON f.n = c.ring_pos % f.total
            WHERE c.starts_at > now()
            ON CONFLICT (screen_id, starts_at) DO NOTHING
            """;

    /* Housekeeping, not correctness. Without it the table grows by about a
       thousand rows a day forever, and none of them is ever read again because
       every query filters on starts_at > now().

       The three NOT EXISTS clauses are V13's, unchanged. A screening somebody
       booked a seat at is their record, and deleting it to tidy up would be
       trading real data for neatness. Those rows stay, in the past, listed by
       nothing.

       make_interval(days => :keepPastDays) is safe here where it was not
       earlier: a bound Integer arrives as int4, which is the type the function
       wants. The version that failed was passing a bigint derived from an id
       column. */
    private static final String PRUNE = """
            DELETE FROM showtime st
            WHERE st.starts_at < now() - make_interval(days => :keepPastDays)
              AND NOT EXISTS (SELECT 1 FROM booking   b  WHERE b.showtime_id  = st.id)
              AND NOT EXISTS (SELECT 1 FROM seat_hold h  WHERE h.showtime_id  = st.id)
              AND NOT EXISTS (SELECT 1 FROM hold_seat hs WHERE hs.showtime_id = st.id)
            """;

    private final JdbcClient jdbc;
    private final SeedProperties properties;

    ShowtimeSeeder(JdbcClient jdbc, SeedProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * One transaction for both statements. If the prune fails, the insert goes
     * with it, which is the honest outcome: a half-applied convergence is a
     * state nobody described.
     */
    @Transactional
    public Result seed() {
        int inserted = jdbc.sql(INSERT)
                .param("lastDay", properties.days() - 1)
                .param("slots", slotList())
                .update();

        int pruned = jdbc.sql(PRUNE)
                .param("keepPastDays", properties.keepPastDays())
                .update();

        return new Result(inserted, pruned);
    }

    /**
     * The slots as "10:30,13:40,16:50,20:00", which string_to_array splits and
     * the cast turns into time[]. A plain String binds with no driver array
     * handling, and the typing has already been done by the config binder.
     */
    private String slotList() {
        return properties.slots().stream()
                .map(LocalTime::toString)
                .collect(Collectors.joining(","));
    }

    public record Result(int inserted, int pruned) {
    }
}