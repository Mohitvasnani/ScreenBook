package com.mohit.screenbook.catalogue;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives every cinema its screens, and every screen its seats.
 *
 * V18 generated both, and did it convergently: each statement ends in a NOT
 * EXISTS that skips any cinema already holding rooms. But it is a versioned
 * migration, so Flyway ran it once and will never run it again. Cinema rows
 * added by a later migration would arrive with no screens, therefore no seats,
 * therefore no screenings, and would sit on the map as buildings nobody can
 * book a ticket at.
 *
 * So the rooms move here, for exactly the reason the screenings did: a
 * migration is a photograph of the moment it ran, and this is a statement of
 * the target state. Every cinema has rooms, every room has seats, and the
 * second run of the day finds that already true and inserts nothing.
 *
 * The SQL below is a verbatim copy of V18's two blocks, deliberately
 * unedited, so the two can be diffed and shown to be the same rules that built
 * the existing 240 screens. V18 keeps its copy because an applied migration
 * cannot be changed without breaking its checksum. This one is now the copy
 * that matters.
 *
 * Room shapes stay hardcoded rather than moving into SeedProperties beside the
 * slot times. Those are numbers a human tunes for a demo; these are the three
 * shapes of room this system models, and a seat map is not a tuning knob.
 */
@Component
public class RoomSeeder {

    /* Screens, from V18.

       The screen count is a function of the name and the id: a recognised
       chain gets three, Raj Mandir gets its single famous hall, everyone else
       gets one or two depending on the parity of their id. Deterministic, so
       a cinema gets the same rooms on every run and the NOT EXISTS below is
       never the only thing standing between this and duplicates.

       Three room shapes picked by a cheap hash on cinema and screen number,
       so a multiplex is varied rather than four copies of one hall. */
    private static final String SCREENS = """
            INSERT INTO screen (cinema_id, name, hall_class, aisle_after)
            SELECT c.id,
                   CASE WHEN p.screens = 1 THEN 'Main Hall' ELSE 'Screen ' || n.n END,
                   CASE WHEN c.name = 'Raj Mandir Cinemas'  THEN 'LUXURY'
                        WHEN p.chain AND n.n = p.screens     THEN 'LUXURY'
                        ELSE 'STANDARD' END,
                   CASE WHEN c.name = 'Raj Mandir Cinemas' THEN '{4,12}'::int[]
                        WHEN (c.id + n.n) % 3 = 0          THEN '{5}'::int[]
                        WHEN (c.id + n.n) % 3 = 1          THEN '{3,9}'::int[]
                        ELSE                                    '{4,12}'::int[]
                   END
            FROM cinema c
            CROSS JOIN LATERAL (
                SELECT c.name ~* '(PVR|INOX|Cinepolis|Cineworld|Odeon|Vue|Everyman|Picturehouse|Omniplex|Carnival|Movietime|Adlabs|Multiplex|IMAX)'
                       AS chain
            ) k
            CROSS JOIN LATERAL (
                SELECT k.chain,
                       CASE WHEN c.name = 'Raj Mandir Cinemas' THEN 1
                            WHEN k.chain                       THEN 3
                            ELSE 1 + (c.id % 2) END AS screens
            ) p
            CROSS JOIN LATERAL generate_series(1, p.screens) AS n(n)
            WHERE NOT EXISTS (SELECT 1 FROM screen s WHERE s.cinema_id = c.id)
            """;

    /* Seats, from V18.

       The room's dimensions are read back off aisle_after rather than stored
       again, because the seat rows already describe the room exactly and a
       second pair of columns would be a second source of truth about it.

       Tier bands are proportional: the front half Normal, the next 30 percent
       Premium, the back 20 Executive. On a ten row screen that is A-E, F-H,
       I-J, which is what V6 hardcoded, so a 14 row room gets the same shape
       instead of running out of tiers. */
    private static final String SEATS = """
            INSERT INTO seat (screen_id, row_label, seat_number, tier)
            SELECT sc.id,
                   chr(64 + r.n),
                   col.n,
                   CASE WHEN r.n <= d.n_rows * 0.5 THEN 'NORMAL'
                        WHEN r.n <= d.n_rows * 0.8 THEN 'PREMIUM'
                        ELSE 'EXECUTIVE' END
            FROM screen sc
            CROSS JOIN LATERAL (
                SELECT CASE WHEN sc.aisle_after = '{5}'::int[]   THEN 8
                            WHEN sc.aisle_after = '{3,9}'::int[] THEN 10
                            ELSE 14 END AS n_rows,
                       CASE WHEN sc.aisle_after = '{5}'::int[]   THEN 10
                            WHEN sc.aisle_after = '{3,9}'::int[] THEN 12
                            ELSE 16 END AS n_cols
            ) d
            CROSS JOIN LATERAL generate_series(1, d.n_rows) AS r(n)
            CROSS JOIN LATERAL generate_series(1, d.n_cols) AS col(n)
            WHERE NOT EXISTS (SELECT 1 FROM seat s WHERE s.screen_id = sc.id)
            """;

    private final JdbcClient jdbc;

    RoomSeeder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One transaction, and the order inside it is load-bearing: seats select
     * from screen, so the screens have to exist first. A new cinema gets both
     * or neither, and a screen with no seats is never visible to anything.
     */
    @Transactional
    public Result seed() {
        int screens = jdbc.sql(SCREENS).update();
        int seats = jdbc.sql(SEATS).update();
        return new Result(screens, seats);
    }

    public record Result(int screens, int seats) {
    }
}