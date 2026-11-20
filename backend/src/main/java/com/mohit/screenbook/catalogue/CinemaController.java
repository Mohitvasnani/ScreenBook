package com.mohit.screenbook.catalogue;

import com.mohit.screenbook.common.ValidationFailedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The list, the city rollup and the detail read go straight to CinemaQuery,
 * because nothing happens to them between the query and the JSON.
 *
 * The two geo endpoints go through CinemaListService, because something now
 * does. A from-price is the pricing rules applied to a screening: it needs a
 * clock, it cannot be a column, and it is the same reason ShowtimeController
 * has a service. The previous version of this comment said there was no
 * service here because a class that forwards three calls is a layer pretending
 * to be a design. That was true of all five endpoints and is now true of
 * three.
 *
 * Validation and the clamp stay here. They are statements about the HTTP
 * contract: what a caller may ask for, and what the response has to admit it
 * actually used.
 */
@RestController
@RequestMapping("/api/cinemas")
public class CinemaController {

    private final CinemaQuery cinemas;
    private final CinemaListService nearby;
    private final int nearestDefault;
    private final int nearestMax;
    private final int withinDefault;
    private final int withinMax;

    CinemaController(CinemaQuery cinemas,
                     CinemaListService nearby,
                     @Value("${screenbook.cinemas.nearest-default-results}") int nearestDefault,
                     @Value("${screenbook.cinemas.nearest-max-results}") int nearestMax,
                     @Value("${screenbook.cinemas.within-default-metres}") int withinDefault,
                     @Value("${screenbook.cinemas.within-max-metres}") int withinMax) {
        this.cinemas = cinemas;
        this.nearby = nearby;
        this.nearestDefault = nearestDefault;
        this.nearestMax = nearestMax;
        this.withinDefault = withinDefault;
        this.withinMax = withinMax;
    }

    /**
     * country is optional, and absent means every cinema, so the call the
     * frontend already makes is unchanged. It is a filter rather than a
     * required parameter because the globe's opening state shows both markets
     * at once, and an unknown code is an empty list rather than a 400, for the
     * reason CinemaQuery.inCountry gives.
     */
    @GetMapping
    public List<CinemaSummary> list(@RequestParam(required = false) String country) {
        return country == null ? cinemas.all() : cinemas.inCountry(country);
    }

    /**
     * One row per city, which is what the globe draws before it zooms in far
     * enough to show individual cinemas.
     *
     * Mapped before {cinemaId} for readability only, like /nearest and
     * /within: a literal path segment beats a path variable whatever the
     * declaration order, so "cities" never reaches the long parser.
     */
    @GetMapping("/cities")
    public List<CinemaCity> cities() {
        return cinemas.cities();
    }

    /**
     * Mapped before the {cinemaId} route in this file for readability only.
     * Spring does not go by declaration order: a literal path segment beats a
     * path variable, so /api/cinemas/nearest reaches this method and never
     * tries to parse "nearest" as a long.
     *
     * limit is an Integer rather than an int with a defaultValue, so that
     * "absent" and "a number the caller chose" stay distinguishable and the
     * default lives in application.properties next to the cap.
     */
    @GetMapping("/nearest")
    public List<NearestCinema> nearest(@RequestParam double lat,
                                       @RequestParam double lon,
                                       @RequestParam(required = false) Integer limit) {
        requireValidPoint(lat, lon);

        int requested = limit == null ? nearestDefault : limit;
        if (requested < 1) {
            throw new ValidationFailedException(
                    "limit must be at least 1, got %d.".formatted(requested));
        }

        /* Clamped rather than rejected. Asking for 500 is not a mistake worth
           an error, it is a client that does not know the cap, and the honest
           response is as many as we will serve. A rejection would make the
           client guess the number. */
        return nearby.nearest(lat, lon, Math.min(requested, nearestMax));
    }

    /**
     * Also mapped before {cinemaId}, and for the same non-reason: a literal
     * path segment beats a path variable whatever the declaration order.
     *
     * The empty-radius fallback lives in the service now, because it needs the
     * same pricing pass as the rows inside the radius and this method has no
     * business knowing there is one.
     */
    @GetMapping("/within")
    public CinemasWithin within(@RequestParam double lat,
                                @RequestParam double lon,
                                @RequestParam(required = false) Integer metres) {
        requireValidPoint(lat, lon);

        int requested = metres == null ? withinDefault : metres;
        if (requested < 1) {
            throw new ValidationFailedException(
                    "metres must be at least 1, got %d.".formatted(requested));
        }

        return nearby.within(lat, lon, Math.min(requested, withinMax));
    }

    @GetMapping("/{cinemaId}")
    public CinemaDetail detail(@PathVariable long cinemaId) {
        return cinemas.detail(cinemaId);
    }

    /**
     * Shared by /nearest and /within so the two coordinate checks cannot drift.
     * The isFinite guard is the important part: NaN compares false against
     * every bound, so the original "lat < -90 || lat > 90" test let NaN
     * through and straight into the spatial query.
     */
    private static void requireValidPoint(double lat, double lon) {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90) {
            throw new ValidationFailedException(
                    "lat must be between -90 and 90, got %s.".formatted(lat));
        }
        if (!Double.isFinite(lon) || lon < -180 || lon > 180) {
            throw new ValidationFailedException(
                    "lon must be between -180 and 180, got %s.".formatted(lon));
        }
    }
}