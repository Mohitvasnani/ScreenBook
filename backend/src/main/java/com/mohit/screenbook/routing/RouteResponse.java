package com.mohit.screenbook.routing;

/**
 * GET /api/cinemas/{cinemaId}/route. coordinates is GeoJSON order, longitude
 * first, so the client can hand it to MapLibre unchanged.
 */
public record RouteResponse(int distanceMetres, int durationSeconds, double[][] coordinates) {
}