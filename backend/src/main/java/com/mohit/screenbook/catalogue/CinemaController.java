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
 * No service between this and CinemaQuery. ShowtimeController has one because
 * pricing has to happen between the query and the JSON; nothing happens here,
 * and a class that forwards three calls is a layer pretending to be a design.
 */
@RestController
@RequestMapping("/api/cinemas")
public class CinemaController {

    private final CinemaQuery cinemas;
    private final int nearestDefault;
    private final int nearestMax;
    private final int withinDefault;
    private final int withinMax;

    CinemaController(CinemaQuery cinemas,
                     @Value("${screenbook.cinemas.nearest-default-results}") int nearestDefault,
                     @Value("${screenbook.cinemas.nearest-max-results}") int nearestMax,
                     @Value("${screenbook.cinemas.within-default-metres}") int withinDefault,
                     @Value("${screenbook.cinemas.within-max-metres}") int withinMax) {
        this.cinemas = cinemas;
        this.nearestDefault = nearestDefault;
        this.nearestMax = nearestMax;
        this.withinDefault = withinDefault;
        this.withinMax = withinMax;
    }

    @GetMapping
    public List<CinemaSummary> list() {
        return cinemas.all();
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
        return cinemas.nearest(lat, lon, Math.min(requested, nearestMax));
    }

    /**
     * Also mapped before {cinemaId}, and for the same non-reason: a literal
     * path segment beats a path variable whatever the declaration order.
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

        int radius = Math.min(requested, withinMax);
        List<NearestCinema> inside = cinemas.within(lat, lon, radius);

        /* A second query, and only when the first found nothing. The case that
           costs a round trip is the case with nothing to render, so the latency
           is free. Folding it into one statement with a window function would
           pay for it on every call to save it on the rare one. */
        NearestCinema fallback = inside.isEmpty()
                ? cinemas.nearest(lat, lon, 1).stream().findFirst().orElse(null)
                : null;

        return new CinemasWithin(radius, inside, fallback);
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