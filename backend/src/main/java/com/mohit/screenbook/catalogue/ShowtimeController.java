package com.mohit.screenbook.catalogue;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @GetMapping
    public List<ShowtimeSummary> list() {
        return showtimes.upcoming(maxResults);
    }

    @GetMapping("/{showtimeId}/seats")
    public SeatMapResponse seats(@PathVariable long showtimeId) {
        return seatMap.forShowtime(showtimeId);
    }
}