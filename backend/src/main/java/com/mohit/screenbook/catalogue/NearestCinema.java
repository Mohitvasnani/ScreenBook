package com.mohit.screenbook.catalogue;

/**
 * One row of GET /api/cinemas/nearest, and of the lists in GET
 * /api/cinemas/within.
 *
 * The fields CinemaSummary also has are repeated rather than composed. A
 * record here is a wire shape, not a domain model, and
 * {"cinema": {...}, "distanceMetres": n} would make every client reach through
 * a wrapper that exists only to record that I did not want to type eight lines
 * twice. The contract reads on its own, which is the thing worth optimising.
 *
 * distanceMetres as an integer. ST_Distance returns a double and sub-metre
 * precision on the position of a building is noise, so this rounds once, here,
 * rather than leaving every client to decide.
 *
 * fromPriceMinor is the cheapest price actually on sale at this cinema inside
 * the from-price window, in the currency this row already names. Null means
 * the cinema has no screening in that window, and that is the only thing it
 * means: both geo endpoints price their rows, so a null is never "this
 * endpoint does not answer that".
 *
 * It is the price after the rules, not the base price. A "from" figure 40
 * percent under anything anyone is charged is worse than no figure, which is
 * why the minimum is taken in Java over priceFor rather than by a SQL
 * aggregate: the five rules key off occupancy, lead time, time of day, day of
 * week and weeks since release, and SQL can only reach the price before any of
 * them has had its say.
 *
 * CinemaQuery constructs these with null, because geography is all it knows.
 * The price arrives in CinemaListService, through withFromPrice.
 */
public record NearestCinema(
        long id,
        String name,
        String city,
        String state,
        String country,
        double latitude,
        double longitude,
        String currency,
        int screenCount,
        int distanceMetres,
        Integer fromPriceMinor
) {

    /**
     * The same cinema with its from-price filled in.
     *
     * A copy rather than a mutable field, so a row cannot be half priced, and
     * so the query keeps returning a complete value rather than a builder for
     * the service to finish.
     */
    public NearestCinema withFromPrice(Integer fromPriceMinor) {
        return new NearestCinema(id, name, city, state, country,
                latitude, longitude, currency, screenCount, distanceMetres,
                fromPriceMinor);
    }
}