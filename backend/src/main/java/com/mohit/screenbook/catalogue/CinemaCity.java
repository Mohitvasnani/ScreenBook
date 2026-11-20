package com.mohit.screenbook.catalogue;

/**
 * One row of GET /api/cinemas/cities. Field names are the JSON keys.
 *
 * What the globe draws below z8. The planet state needs one labelled pin per
 * city, not 980 cinemas, and the pin sits at the mean of that city's cinemas.
 * That mean was computed in the browser over the whole list, which is why the
 * whole list had to be in the browser.
 *
 * cinemaCount is the label's second line and the reason a city is worth
 * clicking. latitude and longitude as plain doubles, for the reason
 * CinemaSummary gives: MapLibre takes two numbers.
 *
 * No currency and no screen count. A city is not a thing with a price or a
 * screen; its cinemas are, and they carry their own.
 */
public record CinemaCity(
        String country,
        String state,
        String city,
        int cinemaCount,
        double latitude,
        double longitude
) {
}