package com.mohit.screenbook.catalogue;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/showtimes")
public class ShowtimeController {

    private final ShowtimeListService showtimes;
    private final SeatMapService seatMap;
    private final int maxResults;

    ShowtimeController(ShowtimeListService showtimes, SeatMapService seatMap,
                       @Value("${screenbook.showtimes.max-results}") int maxResults) {
        this.showtimes = showtimes;
        this.seatMap = seatMap;
        this.maxResults = maxResults;
    }

    /**
     * cinemaId is required, with no default.
     *
     * There is no sensible default. 130 cinemas have 6,366 upcoming screenings
     * between them, and the twelve soonest on earth is not a listing anyone can
     * read: at four slots a day it is tonight at four arbitrary buildings in two
     * countries. "What is on near me" is answered by picking a cinema first,
     * which is what the cinema endpoints are for.
     *
     * A missing parameter is a 400 from Spring itself, in problem+json, so this
     * needs no check of its own.
     */
    @GetMapping
    public List<ShowtimeSummary> list(@RequestParam long cinemaId) {
        return showtimes.upcoming(cinemaId, maxResults);
    }

    /**
     * One screening, so a page reached by URL with only a showtime id does not
     * have to find it in a list it cannot ask for. The seat map and the checkout
     * are both reached that way, and both need the film, the time and the cinema
     * to render a heading.
     */
    @GetMapping("/{showtimeId}")
    public ShowtimeSummary one(@PathVariable long showtimeId) {
        return showtimes.one(showtimeId);
    }

    @GetMapping("/{showtimeId}/seats")
    public SeatMapResponse seats(@PathVariable long showtimeId) {
        return seatMap.forShowtime(showtimeId);
    }
}