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

import com.mohit.screenbook.pricing.PricingRules;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Autowired
    ShowtimeQuery showtimes;

    @Autowired
    PricingRules pricing;

    @Autowired
    Clock clock;

    @Value("${screenbook.cinemas.from-price-window-hours}")
    int fromPriceWindowHours;

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

    /**
     * A fixture by name, with the uniqueness it depends on asserted rather
     * than assumed.
     *
     * V24 made (osm_type, osm_id) the cinema's identity and dropped
     * UNIQUE (city, name), because London has ten cinemas called Everyman and
     * V25 has three called Odeon in Liverpool. A name and a city are still the
     * readable way to name a fixture, so they stay, but .single() turning a
     * second row into IncorrectResultSizeDataAccessException says nothing
     * about why. This fails with the count and with what to do instead.
     *
     * The remaining fixtures, Cinepolis in Jaipur and Everyman in Liverpool,
     * are unique today by luck rather than by constraint. The next ingestion
     * is not obliged to keep it that way, and this is what will say so.
     */
    private long cinemaId(String name, String city) {
        List<Long> ids = jdbc.sql("""
                        SELECT id FROM cinema
                        WHERE name = :name AND city = :city
                        ORDER BY id
                        """)
                .param("name", name)
                .param("city", city)
                .query(Long.class)
                .list();

        assertEquals(1, ids.size(), """
                %d cinemas are called %s in %s, so a name and a city no longer identify one. \
                V24 made (osm_type, osm_id) the identity: pin this fixture by OSM id instead.\
                """.formatted(ids.size(), name, city));

        return ids.getFirst();
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

    /**
     * The filter does not just return GB rows, it accounts for all of them.
     * Two markets that do not sum to the whole list means a cinema has a
     * country that is neither, which V22's foreign key should make impossible
     * and which this would catch if it ever were not.
     */
    @Test
    @DisplayName("the country filter partitions the list and loses nothing")
    void countryFilterPartitionsTheList() {
        List<CinemaSummary> all = cinemas.all();
        List<CinemaSummary> gb = cinemas.inCountry("GB");
        List<CinemaSummary> india = cinemas.inCountry("IN");

        assertEquals(all.size(), gb.size() + india.size(),
                "the two markets do not add up to the whole list");
        assertTrue(gb.stream().allMatch(c -> "GB".equals(c.country())),
                "a non-GB cinema came back from the GB filter");
        assertTrue(india.stream().allMatch(c -> "IN".equals(c.country())),
                "a non-IN cinema came back from the IN filter");
    }

    /**
     * A filter answers "none", it does not refuse the question. The valid set
     * lives in the country table, not in Java.
     */
    @Test
    @DisplayName("an unrecognised country is an empty list, not an error")
    void unknownCountryIsEmpty() {
        assertTrue(cinemas.inCountry("ZZ").isEmpty(),
                "a country code that is not in the country table returned rows");
    }

    /**
     * The join-to-screen trap, pointed at the rollup. If CITIES ever grows a
     * LEFT JOIN screen, every cinema is counted once per screen and this sum
     * jumps to roughly 1,800 against a table of 980. That is the same
     * duplicate-row shape oneRowPerCinema guards, except here it would show up
     * as a pin in the wrong place rather than as a visible extra row.
     */
    @Test
    @DisplayName("the city rollup accounts for every cinema exactly once")
    void cityRollupCountsEveryCinema() {
        List<CinemaCity> cities = cinemas.cities();

        long cinemaRows = jdbc.sql("SELECT count(*) FROM cinema")
                .query(Long.class).single();
        long distinctCities = jdbc.sql("""
                        SELECT count(*)
                        FROM (SELECT DISTINCT country, state, city FROM cinema) d
                        """)
                .query(Long.class).single();

        assertEquals(distinctCities, cities.size(),
                "the rollup has a different number of groups than the table has cities");
        assertEquals(cinemaRows,
                cities.stream().mapToInt(CinemaCity::cinemaCount).sum(),
                "the city counts do not sum to the table, so a join multiplied or dropped rows");
    }

    /**
     * The centroid, checked against the rows it was computed from rather than
     * against a coordinate anyone typed. A mean outside its own extent is
     * arithmetically impossible, so this fails only if ST_Y and ST_X were
     * crossed or the ::geometry cast changed what was collected. Liverpool is
     * near 53N 3W, so a swap puts the latitude at about -2.99, which is a long
     * way outside a range of 53.3 to 53.5.
     *
     * Liverpool because it is the only Liverpool in the data. If a second one
     * ever arrives in another state this filters on city alone and goes wrong,
     * which is the same simplification V25's nearest-centre city assignment
     * already makes.
     */
    @Test
    @DisplayName("the city pin sits inside the extent of its own cinemas")
    void cityPinSitsInsideItsOwnCinemas() {
        CinemaCity liverpool = cinemas.cities().stream()
                .filter(c -> "Liverpool".equals(c.city()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Liverpool in the city rollup"));

        List<CinemaSummary> its = cinemas.all().stream()
                .filter(c -> "Liverpool".equals(c.city()))
                .toList();

        double minLat = its.stream().mapToDouble(CinemaSummary::latitude).min().orElseThrow();
        double maxLat = its.stream().mapToDouble(CinemaSummary::latitude).max().orElseThrow();
        double minLon = its.stream().mapToDouble(CinemaSummary::longitude).min().orElseThrow();
        double maxLon = its.stream().mapToDouble(CinemaSummary::longitude).max().orElseThrow();

        assertTrue(liverpool.latitude() >= minLat && liverpool.latitude() <= maxLat,
                "the city latitude is outside its own cinemas, got " + liverpool.latitude());
        assertTrue(liverpool.longitude() >= minLon && liverpool.longitude() <= maxLon,
                "the city longitude is outside its own cinemas, got " + liverpool.longitude());
        assertEquals(its.size(), liverpool.cinemaCount(),
                "the rollup and the full list disagree on how many cinemas Liverpool has");
    }

    /**
     * Absence of a price is not a price of zero. In tests the showtime seeder
     * is off and V13 only seeded Jaipur, so every GB cinema is in this state,
     * which makes it the cheap half of the contract to pin.
     *
     * The non-empty assertion first, so this cannot pass by finding no
     * cinemas at all and declaring all none of them correct.
     */
    @Test
    @DisplayName("a cinema with no screenings in the window reports no from-price")
    void noScreeningsMeansNoFromPrice() {
        CinemasWithin answer =
                controller.within(LIME_STREET_LAT, LIME_STREET_LON, 20_000);

        assertFalse(answer.cinemas().isEmpty(),
                "no cinemas within 20km of Lime Street, so there is nothing to check");

        for (NearestCinema cinema : answer.cinemas()) {
            assertNull(cinema.fromPriceMinor(),
                    cinema.name() + " reported a from-price with no screenings seeded");
        }
    }

    /**
     * The regression this is really for: taking min() over
     * base_from_price_minor in SQL, which is one line, answerable, and the
     * wrong number, because it is the price before all five rules.
     *
     * There is no assertion that the reported figure equals a minimum computed
     * here, and that is deliberate. The service reads the Clock and so would
     * this test, a few milliseconds apart, and both the window boundary and
     * the lead-time band are decided by that instant. Comparing two
     * clock-derived prices is the trap ShowtimeListingTest.oneAgreesWithTheListing
     * documents: a test that fails once a year is worse than one that checks
     * what it means to check. So this checks the thing that cannot drift,
     * which is that the number is not the base minimum, on the days when the
     * rules move the price at all.
     */
    @Test
    @DisplayName("the from-price is the priced minimum, not the base minimum")
    void fromPriceIsNotTheBaseMinimum() {
        long cinemaId = cinemaId("Cinepolis", "Jaipur");
        CinemaDetail cinepolis = cinemas.detail(cinemaId);

        CinemasWithin answer = controller.within(
                cinepolis.latitude(), cinepolis.longitude(), 20_000);

        Integer reported = answer.cinemas().stream()
                .filter(c -> c.id() == cinemaId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Cinepolis was not in its own radius"))
                .fromPriceMinor();

        assertNotNull(reported, "Cinepolis has seeded screenings and reported no from-price");

        Instant now = clock.instant();
        Instant until = now.plus(Duration.ofHours(fromPriceWindowHours));

        List<ShowtimeRow> inWindow = showtimes.upcomingRows(cinemaId, 500).stream()
                .filter(row -> !row.startsAt().isAfter(until))
                .toList();

        assertFalse(inWindow.isEmpty(),
                "no screenings at Cinepolis in the next " + fromPriceWindowHours + " hours");

        int minBase = inWindow.stream()
                .mapToInt(ShowtimeRow::baseFromPriceMinor)
                .min().orElseThrow();
        int minPriced = inWindow.stream()
                .mapToInt(row -> pricing.priceFor(
                        row.baseFromPriceMinor(), row.facts(), now).adjustedPriceMinor())
                .min().orElseThrow();

        if (minBase != minPriced) {
            assertNotEquals(minBase, reported.intValue(),
                    "the from-price is the base minimum, so the rules were never applied");
        }
    }

    /**
     * The fold turns one row per screening into one row per cinema, and this
     * is what says it neither drops a cinema nor keeps a screening.
     *
     * The crash case is covered too, loudly: Collectors.toMap without its
     * merge function throws IllegalStateException on the second screening at
     * any cinema, so a missing Math::min fails this before the assertions run.
     */
    @Test
    @DisplayName("pricing changes the prices and not the shape of the list")
    void pricingDoesNotChangeTheShapeOfTheList() {
        CinemaDetail cinepolis = cinemas.detail(cinemaId("Cinepolis", "Jaipur"));
        double lat = cinepolis.latitude();
        double lon = cinepolis.longitude();

        List<NearestCinema> geography = cinemas.within(lat, lon, 20_000);
        CinemasWithin priced = controller.within(lat, lon, 20_000);

        assertEquals(geography.size(), priced.cinemas().size(),
                "the priced list and the geography disagree on how many cinemas are in the radius");
        assertEquals(geography.stream().map(NearestCinema::id).toList(),
                priced.cinemas().stream().map(NearestCinema::id).toList(),
                "pricing reordered the radius");
        assertTrue(priced.cinemas().stream().anyMatch(c -> c.fromPriceMinor() != null),
                "no cinema near Jaipur got a price, so the price query matched nothing");
    }
}