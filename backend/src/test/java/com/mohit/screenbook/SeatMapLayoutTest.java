package com.mohit.screenbook;

import com.mohit.screenbook.catalogue.SeatMapResponse;
import com.mohit.screenbook.catalogue.SeatMapSeat;
import com.mohit.screenbook.catalogue.SeatMapService;
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
 * The seat map draws the room the database says exists.
 *
 * No @Transactional. These only read.
 *
 * One limitation worth naming rather than hiding. With the seeder off in
 * tests, V13's Cinepolis Jaipur screenings are the only showtimes that exist,
 * and V16 gave V3's four screens the {3,9} default, so {3,9} is the only
 * shape reachable end to end here without inserting a showtime. The second
 * test covers the gap from the other side: it asserts the other two shapes
 * exist in the data at all, so a regression that flattened every room to one
 * layout would still be caught.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class SeatMapLayoutTest {

    @Autowired
    SeatMapService seatMaps;

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("the seat map carries its screen's own aisle positions")
    void theSeatMapCarriesTheRoomShape() {
        SeatMapResponse map = seatMaps.forShowtime(aShowtimeAtCinepolisJaipur());

        assertEquals(List.of(3, 9), map.aisleAfter(),
                "the 3 | 6 | 3 room the frontend used to hardcode");

        /* An aisle at or past the last seat is an aisle against the wall,
           which is not a room, and it is the shape of mistake that renders as
           an empty trailing block rather than as an error. */
        int width = map.seats().stream()
                .mapToInt(SeatMapSeat::seatNumber)
                .max()
                .orElseThrow();

        assertTrue(map.aisleAfter().stream().allMatch(a -> a >= 1 && a < width),
                "every aisle position must fall inside the row, width " + width);
    }

    @Test
    @DisplayName("every screen states a layout, and V18 produced varied rooms")
    void theRoomShapesAreVaried() {
        long noLayout = jdbc.sql(
                        "SELECT count(*) FROM screen WHERE cardinality(aisle_after) = 0")
                .query(Long.class)
                .single();

        assertEquals(0, noLayout, "a screen with no aisle positions states no room");

        /* Update this list if a fourth room shape is ever added to V18's
           generator. It is pinned rather than counted so that losing one shape
           and gaining another cannot pass. */
        List<String> shapes = jdbc.sql(
                        "SELECT DISTINCT aisle_after::text FROM screen ORDER BY 1")
                .query(String.class)
                .list();

        assertEquals(List.of("{3,9}", "{4,12}", "{5}"), shapes,
                "V18 generates three room shapes by a hash of the cinema and screen");
    }

    private long aShowtimeAtCinepolisJaipur() {
        return jdbc.sql("""
                        SELECT st.id
                        FROM showtime st
                        JOIN screen sc ON sc.id = st.screen_id
                        JOIN cinema ci ON ci.id = sc.cinema_id
                        WHERE ci.name = 'Cinepolis' AND ci.city = 'Jaipur'
                        ORDER BY st.starts_at
                        LIMIT 1
                        """)
                .query(Long.class)
                .single();
    }
}