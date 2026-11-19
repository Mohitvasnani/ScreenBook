
package com.mohit.screenbook.catalogue;

import java.util.List;

/**
 * GET /api/cinemas/within.
 *
 * An object rather than the bare array /nearest returns, because there are two
 * things to say here and only one of them is a list.
 *
 * nearestOutside is null whenever cinemas is non-empty. It exists so an empty
 * radius can be answered with "nothing within 10km, the closest is 34km away"
 * instead of a blank panel. V16's reasoning about the nearest list was that an
 * empty result is a worse answer than a long drive; this is that reasoning
 * honoured rather than dodged, because the client can now say both things.
 *
 * radiusMetres is what was used, not what was asked for. A caller that asks
 * for 500km gets the cap, and a response that does not say so leaves it
 * drawing a circle that does not match the rows inside it.
 */
public record CinemasWithin(int radiusMetres,
                            List<NearestCinema> cinemas,
                            NearestCinema nearestOutside) {
}