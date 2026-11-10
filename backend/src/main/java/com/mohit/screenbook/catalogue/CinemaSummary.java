package com.mohit.screenbook.catalogue;

/**
 * One row of GET /api/cinemas. Field names are the JSON keys.
 *
 * latitude and longitude as plain doubles rather than GeoJSON. The map needs
 * two numbers to place a marker and MapLibre takes them as [lon, lat]; wrapping
 * them in a Point object would be a shape the client has to unwrap for nothing.
 *
 * No address here. 130 addresses is most of this payload's weight and a map
 * marker does not show one. The detail endpoint has it.
 *
 * currency so the client can format a price before it has loaded a showtime,
 * which is what a cinema card with "from" prices on it will want.
 */
public record CinemaSummary(
        long id,
        String name,
        String city,
        String state,
        double latitude,
        double longitude,
        String currency,
        int screenCount
) {
}