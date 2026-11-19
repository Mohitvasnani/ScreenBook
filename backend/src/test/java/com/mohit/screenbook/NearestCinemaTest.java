package com.mohit.screenbook;

import com.mohit.screenbook.catalogue.*;
import com.mohit.screenbook.common.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The geography, which is the part of this wave that has no other way to be
 * checked. Every cinema row comes from V18, so these assertions are against
 * data a migration put there and are the same on every machine.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class NearestCinemaTest {

    /** Lime Street station. */
    private static final double LIME_STREET_LAT = 53.4078;
    private static final double LIME_STREET_LON = -2.9777;

    @Autowired
    CinemaQuery cinemas;

    @Autowired
    JdbcClient jdbc;
    @Autowired
    CinemaController controller;

    @Value("${screenbook.cinemas.within-max-metres}")
    int withinMax;

    /**
     * The one that catches a sphere against spheroid mismatch.
     *
     * ORDER BY uses the KNN operator and the distance column uses ST_Distance,
     * which are two different functions over the same geography. ST_Distance
     * defaults to the spheroid and <-> does not, so with that default the
     * ordering and the numbers disagree by about 0.3 percent and a list can
     * show 2,411m above 2,409m. CinemaQuery passes use_spheroid false to keep
     * them on one model, and this is what says so.
     */
    @Test
    @DisplayName("the nearest list is ordered by the distance it reports")
    void orderingAgreesWithTheDistances() {
        List<NearestCinema> near = cinemas.nearest(LIME_STREET_LAT, LIME_STREET_LON, 5);

        assertEquals(5, near.size(), "there are 130 cinemas, so five were asked for and five expected");
        for (int i = 1; i < near.size(); i++) {
            assertTrue(near.get(i - 1).distanceMetres() <= near.get(i).distanceMetres(),
                    "distances are not ascending at position " + i
                            + ": the ordering and the reported distance use different models");
        }
    }

    /**
     * The reason UK cinemas were added at all. From Liverpool an India-only
     * dataset returns nothing inside 7,000km.
     */
    @Test
    @DisplayName("from Lime Street the nearest cinemas are in Liverpool")
    void findsTheLocalOnes() {
        List<NearestCinema> near = cinemas.nearest(LIME_STREET_LAT, LIME_STREET_LON, 3);

        assertTrue(near.stream().allMatch(c -> "Liverpool".equals(c.city())),
                "the three nearest to Lime Street were not all Liverpool cinemas");
        assertTrue(near.getFirst().distanceMetres() < 2_000,
                "the nearest cinema to Lime Street should be walking distance, got "
                        + near.getFirst().distanceMetres() + "m");
    }

    /**
     * ST_Y is latitude and ST_X is longitude, but the column was built by
     * ST_MakePoint(lon, lat), so the two conventions are opposite and a swap
     * here would be invisible on a map of one city and wrong everywhere else.
     * Liverpool is near 53N 3W, and a swap would report a latitude of -2.98,
     * which is in the Atlantic off Africa.
     */
    @Test
    @DisplayName("latitude and longitude are not swapped")
    void coordinatesComeBackTheRightWayRound() {
        CinemaDetail everyman = cinemas.detail(cinemaId("Everyman", "Liverpool"));

        assertTrue(everyman.latitude() > 53 && everyman.latitude() < 54,
                "latitude was " + everyman.latitude() + ", expected about 53.4");
        assertTrue(everyman.longitude() > -3.1 && everyman.longitude() < -2.9,
                "longitude was " + everyman.longitude() + ", expected about -2.99");
    }

    /**
     * The duplicate-row bug, pointed at the new query. Every join added in this
     * wave has had a version that matched twice, and a LEFT JOIN to screen with
     * a GROUP BY is exactly that shape: get the grouping wrong and a four
     * screen multiplex becomes four rows on the map.
     */
    @Test
    @DisplayName("the map list has one row per cinema")
    void oneRowPerCinema() {
        List<CinemaSummary> all = cinemas.all();

        long cinemaRows = jdbc.sql("SELECT count(*) FROM cinema").query(Long.class).single();

        assertEquals(cinemaRows, all.size(),
                "the list and the table disagree, so the join duplicated or dropped cinemas");
        assertEquals(all.size(), all.stream().map(CinemaSummary::id).distinct().count(),
                "the same cinema appeared more than once");
    }

    /**
     * Four, not seven. V18 reassigned V3's four screens to Cinepolis and the
     * generated-screens insert skips any cinema that already has one, so this
     * also pins that NOT EXISTS clause.
     */
    @Test
    @DisplayName("the screen count is the cinema's own screens")
    void countsScreens() {
        CinemaDetail cinepolis = cinemas.detail(cinemaId("Cinepolis", "Jaipur"));

        assertEquals(4, cinepolis.screenCount());
        assertEquals("INR", cinepolis.currency());
        assertEquals("Asia/Kolkata", cinepolis.timezone());
    }

    @Test
    @DisplayName("a cinema that does not exist is a 404")
    void unknownCinemaIsNotFound() {
        assertThrows(NotFoundException.class, () -> cinemas.detail(-1L));
    }

    private long cinemaId(String name, String city) {
        return jdbc.sql("SELECT id FROM cinema WHERE name = :name AND city = :city")
                .param("name", name)
                .param("city", city)
                .query(Long.class)
                .single();
    }

    /**
     * The boundary, and the test the use_spheroid flag on ST_DWithin exists
     * for. The radius is not hardcoded: it is taken from the third nearest
     * cinema's own reported distance, so the assertion calibrates itself
     * against whatever V18 actually contains.
     *
     * One metre either side is enough because distanceMetres is rounded, so
     * the true distance is within half a metre of it. It is also tight enough
     * to fail if ST_DWithin were measuring on the spheroid while ST_Distance
     * measured on the sphere: that gap is about 0.3 percent, which at this
     * range is several metres, not one.
     */
    @Test
    @DisplayName("the radius boundary includes and excludes the cinema sitting on it")
    void radiusBoundaryIsExact() {
        NearestCinema third = cinemas.nearest(LIME_STREET_LAT, LIME_STREET_LON, 3).getLast();
        int onTheLine = third.distanceMetres();

        List<NearestCinema> justInside =
                cinemas.within(LIME_STREET_LAT, LIME_STREET_LON, onTheLine + 1);
        List<NearestCinema> justOutside =
                cinemas.within(LIME_STREET_LAT, LIME_STREET_LON, onTheLine - 1);

        assertTrue(justInside.stream().anyMatch(c -> c.id() == third.id()),
                "a cinema " + onTheLine + "m away was missing from a radius of "
                        + (onTheLine + 1) + "m");
        assertTrue(justOutside.stream().noneMatch(c -> c.id() == third.id()),
                "a cinema " + onTheLine + "m away appeared in a radius of "
                        + (onTheLine - 1) + "m");
    }

    /**
     * Fair Isle, between Orkney and Shetland. The nearest cinema in the
     * dataset is several hundred kilometres away, which is the case the
     * fallback exists for: an empty list plus the one cinema outside it, so
     * the client can say how far away the closest thing is instead of showing
     * a blank panel.
     */
    @Test
    @DisplayName("an empty radius answers with the nearest cinema outside it")
    void emptyRadiusFallsBackToTheNearest() {
        CinemasWithin answer = controller.within(59.53, -1.63, 20_000);

        assertTrue(answer.cinemas().isEmpty(),
                "Fair Isle unexpectedly has a cinema within 20km");
        assertNotNull(answer.nearestOutside(),
                "the radius was empty and no fallback was offered, which is the blank screen");
        assertTrue(answer.nearestOutside().distanceMetres() > 20_000,
                "the fallback is inside the radius, so it should have been in the list");
    }

    /**
     * The clamp, read back off the response. A client that asks for 500km has
     * to be told it got 50, or it draws a circle with nothing in most of it.
     * The expected value comes from the property rather than a literal, so the
     * test does not have to be edited when the cap changes.
     */
    @Test
    @DisplayName("a radius over the cap comes back as the cap")
    void radiusIsClamped() {
        CinemasWithin answer =
                controller.within(LIME_STREET_LAT, LIME_STREET_LON, 500_000);

        assertEquals(withinMax, answer.radiusMetres(),
                "the response did not report the radius it actually used");
    }
}