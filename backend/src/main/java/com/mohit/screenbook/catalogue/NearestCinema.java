package com.mohit.screenbook.catalogue;

/**
 * One row of GET /api/cinemas/nearest.
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
 */
public record NearestCinema(
        long id,
        String name,
        String city,
        String state,
        double latitude,
        double longitude,
        String currency,
        int screenCount,
        int distanceMetres
) {
}