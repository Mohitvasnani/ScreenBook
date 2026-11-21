package com.mohit.screenbook.routing;

import com.mohit.screenbook.catalogue.CinemaDetail;
import com.mohit.screenbook.catalogue.CinemaQuery;
import com.mohit.screenbook.common.RoutingUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import com.mohit.screenbook.common.RateLimitedException;
import com.mohit.screenbook.ratelimit.RateLimitProperties;
import com.mohit.screenbook.ratelimit.RateLimiter;
import java.util.List;
import java.util.Map;

/**
 * Road geometry from OpenRouteService. The only job here is to turn a snapped
 * origin and a cinema into a path, and to make every failure look the same to
 * the caller.
 */
@Service
public class RoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    /** One bucket for the whole service: the quota belongs to the API key, not to a caller. */
    private static final String PROVIDER_BUDGET = "rl:routing:provider";
    record OrsResponse(List<Feature> features) {}
    record Feature(Geometry geometry, Properties properties) {}
    record Geometry(double[][] coordinates) {}
    record Properties(Summary summary) {}
    record Summary(double distance, double duration) {}

    private final RestClient http;
    private final CinemaQuery cinemas;
    private final String apiKey;
    private final RateLimiter limiter;
    private final RateLimitProperties.Bucket budget;

    RoutingService(RestClient.Builder builder, RoutingProperties properties, CinemaQuery cinemas,
                   RateLimiter limiter, RateLimitProperties limits) {
        this.apiKey = properties.apiKey() == null ? "" : properties.apiKey();
        this.cinemas = cinemas;
        this.limiter = limiter;
        this.budget = limits.routing();
        this.http = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader("Authorization", apiKey)
                .build();
    }

    /* The key is the argument, so the controller has to snap before calling.
       A call from inside this class would skip the cache. */
    @Cacheable("routes")
    public RouteResponse route(RouteKey key) {
        if (apiKey.isBlank()) {
            throw new RoutingUnavailableException("Routing is not configured.");
        }

        CinemaDetail cinema = cinemas.detail(key.cinemaId());

        /* The budget is spent here: after the checks that need no provider,
           before the one call that does. A cache hit never reaches this line,
           so only a request that would really cost a provider call counts.
           A refusal throws, and @Cacheable does not cache an exception. */
        RateLimiter.Decision decision = limiter.tryAcquire(
                PROVIDER_BUDGET, budget.capacity(), budget.refillPerSecond());
        if (!decision.allowed()) {
            throw new RateLimitedException(
                    "Routing is busy. Try again shortly.", decision.retryAfterSeconds());
        }
        OrsResponse response;
        try {
            response = http.post()
                    .uri("/directions/driving-car/geojson")
                    .body(Map.of("coordinates", List.of(
                            List.of(key.lon(), key.lat()),
                            List.of(cinema.longitude(), cinema.latitude()))))
                    .retrieve()
                    .body(OrsResponse.class);
        } catch (RestClientResponseException ex) {
            log.warn("Routing provider answered {}", ex.getStatusCode());
            throw new RoutingUnavailableException("The routing provider refused the request.");
        } catch (RestClientException ex) {
            log.warn("Routing provider unreachable: {}", ex.getClass().getSimpleName());
            throw new RoutingUnavailableException("The routing provider could not be reached.");
        }

        if (response == null || response.features() == null || response.features().isEmpty()) {
            throw new RoutingUnavailableException("The routing provider returned no route.");
        }

        Feature route = response.features().get(0);
        if (route.geometry() == null || route.properties() == null
                || route.properties().summary() == null) {
            throw new RoutingUnavailableException("The routing provider returned no route.");
        }

        Summary summary = route.properties().summary();
        return new RouteResponse(
                (int) Math.round(summary.distance()),
                (int) Math.round(summary.duration()),
                route.geometry().coordinates());
    }
}