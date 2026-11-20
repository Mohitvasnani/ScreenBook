package com.mohit.screenbook.routing;

import com.mohit.screenbook.common.ValidationFailedException;

/**
 * Cache key and request in one. The origin is snapped to a grid of about 110
 * metres before either is built, so a cached answer is exactly what the
 * provider would have returned for the key, and the provider never sees a
 * precise location. Validation lives here so a key cannot exist with a point
 * that is not on the planet.
 */
public record RouteKey(long cinemaId, double lat, double lon) {

    private static final double GRID = 1000.0;

    public static RouteKey snap(long cinemaId, double lat, double lon) {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90) {
            throw new ValidationFailedException(
                    "lat must be between -90 and 90, got %s.".formatted(lat));
        }
        if (!Double.isFinite(lon) || lon < -180 || lon > 180) {
            throw new ValidationFailedException(
                    "lon must be between -180 and 180, got %s.".formatted(lon));
        }
        return new RouteKey(
                cinemaId,
                Math.round(lat * GRID) / GRID,
                Math.round(lon * GRID) / GRID);
    }
}