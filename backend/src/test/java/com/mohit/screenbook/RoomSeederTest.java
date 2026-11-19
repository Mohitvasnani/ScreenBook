package com.mohit.screenbook;

import com.mohit.screenbook.catalogue.RoomSeeder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Transactional, like ShowtimeSeederTest, so the cinema this inserts and the
 * rooms the seeder builds for it both roll back and no other test class sees
 * a fictional Liverpool venue.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RoomSeederTest {

    @Autowired
    RoomSeeder seeder;

    @Autowired
    JdbcClient jdbc;

    /**
     * The whole reason this class exists. V18 built rooms for the cinemas that
     * existed when it ran; the next migration adds hundreds more, and nobody is
     * going to notice by hand that they have no seats.
     *
     * The address is deliberately null, which V24 now permits and which most of
     * the incoming OSM rows will be.
     */
    @Test
    @DisplayName("a cinema added after the migrations gets rooms on the next run")
    void convergesOnNewCinemas() {
        long cinemaId = jdbc.sql("""
                        INSERT INTO cinema
                            (name, city, state, address, currency, timezone,
                             location, country, osm_type, osm_id)
                        VALUES ('Test Hall', 'Liverpool', 'England', NULL,
                                'GBP', 'Europe/London',
                                ST_SetSRID(ST_MakePoint(-2.98, 53.40), 4326)::geography,
                                'GB', 'node', 999999999999)
                        RETURNING id
                        """)
                .query(Long.class)
                .single();

        RoomSeeder.Result result = seeder.seed();

        assertTrue(result.screens() > 0, "the new cinema was given no screens");
        assertTrue(result.seats() > 0, "the new screens were given no seats");

        long screens = jdbc.sql("SELECT count(*) FROM screen WHERE cinema_id = :id")
                .param("id", cinemaId)
                .query(Long.class)
                .single();

        long seats = jdbc.sql("""
                        SELECT count(*)
                        FROM seat s
                        JOIN screen sc ON sc.id = s.screen_id
                        WHERE sc.cinema_id = :id
                        """)
                .param("id", cinemaId)
                .query(Long.class)
                .single();

        assertTrue(screens >= 1, "expected at least one screen, got " + screens);
        assertTrue(seats >= 80,
                "the smallest room this system models is 8 rows of 10, got " + seats);
    }

    /**
     * The property the design rests on, same as the showtime seeder's. This
     * runs on every boot forever.
     */
    @Test
    @DisplayName("a second run creates nothing")
    void isIdempotent() {
        seeder.seed();
        RoomSeeder.Result second = seeder.seed();

        assertEquals(0, second.screens(), "the second run created screens that existed");
        assertEquals(0, second.seats(), "the second run created seats that existed");
    }

    /**
     * No cinema anywhere without rooms. Cheap to assert, and it is the
     * invariant the runner promises on every startup.
     */
    @Test
    @DisplayName("every cinema has at least one screen and every screen has seats")
    void nothingIsRoomless() {
        seeder.seed();

        long roomless = jdbc.sql("""
                        SELECT count(*) FROM cinema c
                        WHERE NOT EXISTS (SELECT 1 FROM screen s WHERE s.cinema_id = c.id)
                        """)
                .query(Long.class)
                .single();

        long seatless = jdbc.sql("""
                        SELECT count(*) FROM screen sc
                        WHERE NOT EXISTS (SELECT 1 FROM seat s WHERE s.screen_id = sc.id)
                        """)
                .query(Long.class)
                .single();

        assertEquals(0, roomless, roomless + " cinemas have no screens");
        assertEquals(0, seatless, seatless + " screens have no seats");
    }
}