package com.mohit.screenbook.routing;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The road route from a point to one of our cinemas. Only cinemas we hold, so
 * this cannot be used to route between arbitrary places.
 */
@RestController
@RequestMapping("/api/cinemas")
public class RouteController {

    private final RoutingService routing;

    RouteController(RoutingService routing) {
        this.routing = routing;
    }

    @GetMapping("/{cinemaId}/route")
    public RouteResponse route(@PathVariable long cinemaId,
                               @RequestParam double lat,
                               @RequestParam double lon) {
        return routing.route(RouteKey.snap(cinemaId, lat, lon));
    }
}