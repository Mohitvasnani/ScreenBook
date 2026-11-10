package com.mohit.screenbook.catalogue;

/**
 * GET /api/cinemas/{cinemaId}.
 *
 * timezone is sent because the client formats showtimes in the cinema's local
 * time, not the browser's. A Liverpool user looking at Jaipur should see 20:00,
 * which is when the film starts for the people in the room, and startsAt is an
 * instant so the client cannot work that out alone.
 *
 * No list of screens. A customer picks a showtime, not a screen, and the
 * showtimes list already carries screenName. screenCount is the only fact
 * about the building's screens that a detail page shows.
 */
public record CinemaDetail(
        long id,
        String name,
        String city,
        String state,
        String address,
        double latitude,
        double longitude,
        String currency,
        String timezone,
        int screenCount
) {
}