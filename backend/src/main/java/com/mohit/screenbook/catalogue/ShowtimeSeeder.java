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
 *   cinema, drawn only from films released in that cinema's country, and none
 *   in the past
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

       The film subquery is a ring, and as of V22 it is one ring per territory
       rather than one ring for the world. row_number numbers the films
       released in a country from zero, count(*) gives that country's total,
       and "the next film" is ring_pos mod total inside GB or inside IN and
       never across the two. Both windows carry the same PARTITION BY, which
       is the whole mechanism: partitioning restarts the numbering at every
       new region and scopes the count to that region's rows. Without it,
       row_number would run 0..66 across both countries and count(*) would
       return 67 everywhere, which is exactly the bug this replaces.

       The join is region first and modulo second. f.total is per territory
       now, so the modulo only means anything once both sides agree which
       country they are talking about. c.country comes from the cinema, which
       is the fact V22 added; before it there was nothing on the row to join
       on, which is why this query could not have been written in wave 5.

       film is not joined at all. film_region.film_id is everything the insert
       needs, and the foreign key already guarantees it points at a real film,
       so joining film would read a table to prove something a constraint
       proves for free. ORDER BY fr.film_id gives the same stable order the
       old ORDER BY id did.

       The ring position is the screen and the day, and deliberately not the
       slot. A screen runs one film across all four of its daily slots, which
       is how a cinema actually schedules and what makes the grouped listing
       read properly: four screens give four films a day with four times each.
       With slot.idx in the position this worked only by accident, because 3
       films meant "% 3" collided constantly. At 67 films it almost never
       collides, so every slot got a different film and a four-screen cinema
       advertised sixteen films a day with one screening each.

       A country with no film_region rows gets no partition, so the join finds
       nothing and that country's screens are left alone. That is the same
       shape as the old "no films at all" case: the modulo is never evaluated
       against a zero, because a partition only exists if it has rows in it.
       No division by zero to guard, and no special case to write.

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
            SELECT f.film_id, c.screen_id, c.starts_at
            FROM (
                    SELECT sc.id      AS screen_id,
                           ci.country AS country,

                           (CAST(now() AT TIME ZONE ci.timezone AS date)
                                + d.n
                                + slot.time_of_day
                                + (sc.id % 4) * interval '5 minutes')
                             AT TIME ZONE ci.timezone        AS starts_at,

                           sc.id + d.n                       AS ring_pos

                    FROM screen sc
                    JOIN cinema ci ON ci.id = sc.cinema_id
                    CROSS JOIN generate_series(0, :lastDay) AS d(n)
                    CROSS JOIN unnest(CAST(string_to_array(:slots, ',') AS time[]))
                                                            AS slot(time_of_day)
                 ) c
            JOIN (
                    SELECT fr.region,
                           fr.film_id,
                           row_number() OVER (PARTITION BY fr.region
                                              ORDER BY fr.film_id) - 1 AS n,
                           count(*)     OVER (PARTITION BY fr.region)  AS total
                    FROM film_region fr
                 ) f ON  f.region = c.country
                     AND f.n      = c.ring_pos % f.total
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