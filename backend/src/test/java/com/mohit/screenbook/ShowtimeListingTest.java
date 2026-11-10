package com.mohit.screenbook;

import com.mohit.screenbook.catalogue.ShowtimeListService;
import com.mohit.screenbook.catalogue.ShowtimeSummary;
import com.mohit.screenbook.common.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The listing is scoped to one cinema.
 *
 * No @Transactional. These only read, so there is nothing to roll back, and
 * adding one would wrap the service's own read-only transaction in a second
 * one for no reason.
 *
 * The test data comes from the migrations, not from this class. V13 seeds a
 * week of screenings relative to now() and V18 attaches V3's four screens to
 * Cinepolis Jaipur, so in a fresh container exactly one cinema has screenings
 * and the other 129 have none. That is the shape these tests need and it costs
 * no setup.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class ShowtimeListingTest {

    @Autowired
    ShowtimeListService showtimes;

    @Autowired
    JdbcClient jdbc;

    /**
     * The reason the parameter was added. Before it, this endpoint returned the
     * twelve soonest screenings anywhere, which with the seeder running means
     * four buildings in two countries.
     */
    @Test
    @DisplayName("a listing contains only the screenings at the cinema asked for")
    void isScopedToOneCinema() {
        long cinemaId = cinemaId("Cinepolis", "Jaipur");

        List<ShowtimeSummary> listing = showtimes.upcoming(cinemaId, 12);

        assertFalse(listing.isEmpty(),
                "Cinepolis has V13's screenings, so an empty listing means the filter is wrong");
        assertTrue(listing.stream().allMatch(summary -> summary.cinemaId() == cinemaId),
                "a row reported a cinema other than the one asked for");

        /* The screen names are the check, because screen is the table the
           filter actually runs against. V3 named Cinepolis's four screens
           Screen 1 to Screen 4 and every generated screen elsewhere is named
           the same way, so names alone would not prove scoping. Ids do. */
        List<Long> screenIdsHere = jdbc.sql("SELECT id FROM screen WHERE cinema_id = :cinemaId")
                .param("cinemaId", cinemaId)
                .query(Long.class)
                .list();

        List<Long> screenIdsListed = jdbc.sql("""
                        SELECT DISTINCT st.screen_id
                        FROM showtime st
                        WHERE st.id IN (:ids)
                        """)
                .param("ids", listing.stream().map(ShowtimeSummary::id).toList())
                .query(Long.class)
                .list();

        assertTrue(screenIdsHere.containsAll(screenIdsListed),
                "the listing included a screening on a screen at another cinema");
    }

    /**
     * The currency bug this wave hit three times, as an assertion rather than a
     * comment. An Indian cinema's prices must come off the INR ladder, and the
     * INR ladder's cheapest row is 20000 paise against the GBP ladder's 900
     * pence, so a wrong join is visible in the number itself.
     */
    @Test
    @DisplayName("an Indian cinema is priced in rupees")
    void pricesInTheCinemasOwnCurrency() {
        List<ShowtimeSummary> listing = showtimes.upcoming(cinemaId("Cinepolis", "Jaipur"), 12);

        assertTrue(listing.stream().allMatch(s -> "INR".equals(s.currency())),
                "a Jaipur screening was listed in a currency that is not INR");
        assertTrue(listing.stream().allMatch(s -> s.basePriceMinor() >= 20_000),
                "a base price below the cheapest INR row means the price join picked up GBP");
    }

    @Test
    @DisplayName("the listing is ordered by start time, soonest first")
    void isOrderedByStartTime() {
        List<ShowtimeSummary> listing = showtimes.upcoming(cinemaId("Cinepolis", "Jaipur"), 12);

        Instant now = Instant.now();
        for (int i = 0; i < listing.size(); i++) {
            assertTrue(listing.get(i).startsAt().isAfter(now),
                    "a screening that has already started was listed");
            if (i > 0) {
                assertFalse(listing.get(i).startsAt().isBefore(listing.get(i - 1).startsAt()),
                        "the listing is out of order at position " + i);
            }
        }
    }

    /**
     * The branch that makes the 404 worth its extra query. A real cinema with
     * nothing on is an empty list, not a 404, and only the second query can
     * tell the two apart. Every UK cinema is in this state in tests, because
     * the seeder is switched off and V13 only seeded Jaipur.
     */
    @Test
    @DisplayName("a real cinema with no screenings is an empty list, not a 404")
    void emptyIsNotMissing() {
        List<ShowtimeSummary> listing = showtimes.upcoming(cinemaId("Odeon", "Liverpool"), 12);

        assertEquals(List.of(), listing);
    }

    @Test
    @DisplayName("a cinema that does not exist is a 404")
    void unknownCinemaIsNotFound() {
        assertThrows(NotFoundException.class, () -> showtimes.upcoming(-1L, 12));
    }

    /**
     * The single read and the listing must describe the same screening, because
     * the seat map page uses the first and the cinema page uses the second and a
     * customer moves between them in one click.
     *
     * Field by field rather than record equality, deliberately. priceMinor is
     * computed against the clock, so two reads a millisecond apart could in
     * principle land either side of a lead-time boundary, and a test that fails
     * once a year at 06:00 is worse than one that checks what it means to check.
     */
    @Test
    @DisplayName("a screening read on its own matches the same screening in a listing")
    void oneAgreesWithTheListing() {
        long cinemaId = cinemaId("Cinepolis", "Jaipur");
        List<ShowtimeSummary> listing = showtimes.upcoming(cinemaId, 12);
        assertFalse(listing.isEmpty());

        ShowtimeSummary inListing = listing.getFirst();
        ShowtimeSummary alone = showtimes.one(inListing.id());

        assertEquals(inListing.id(), alone.id());
        assertEquals(inListing.cinemaId(), alone.cinemaId());
        assertEquals(inListing.filmTitle(), alone.filmTitle());
        assertEquals(inListing.screenName(), alone.screenName());
        assertEquals(inListing.startsAt(), alone.startsAt());
        assertEquals(inListing.currency(), alone.currency());
        assertEquals(inListing.basePriceMinor(), alone.basePriceMinor());
        assertEquals(inListing.seatsTotal(), alone.seatsTotal());
    }

    @Test
    @DisplayName("a screening that does not exist is a 404")
    void unknownShowtimeIsNotFound() {
        assertThrows(NotFoundException.class, () -> showtimes.one(-1L));
    }

    private long cinemaId(String name, String city) {
        return jdbc.sql("SELECT id FROM cinema WHERE name = :name AND city = :city")
                .param("name", name)
                .param("city", city)
                .query(Long.class)
                .single();
    }
}