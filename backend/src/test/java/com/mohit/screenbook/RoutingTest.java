package com.mohit.screenbook;

import com.mohit.screenbook.catalogue.CinemaQuery;
import com.mohit.screenbook.common.NotFoundException;
import com.mohit.screenbook.common.RoutingUnavailableException;
import com.mohit.screenbook.common.ValidationFailedException;
import com.mohit.screenbook.routing.RouteController;
import com.mohit.screenbook.routing.RouteResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The routing endpoint against a fake provider. The provider is a real HTTP
 * server on a random port, so the real client, the real cache proxy and the
 * real failure mapping all run. Only the third party is fake.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class RoutingTest {

    private static final double LAT = 53.4084;
    private static final double LON = -2.9916;

    private static final String ROUTE_JSON = """
            {"features":[{"geometry":{"type":"LineString","coordinates":\
            [[-2.9916,53.4084],[-2.9864,53.4072]]},\
            "properties":{"summary":{"distance":612.4,"duration":131.6}}}]}""";

    private static final AtomicInteger hits = new AtomicInteger();
    private static volatile int providerStatus = 200;
    private static volatile String lastAuthorization;
    private static volatile String lastQuery;

    private static final HttpServer provider = startProvider();

    private static HttpServer startProvider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                hits.incrementAndGet();
                lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                lastQuery = exchange.getRequestURI().getRawQuery();
                exchange.getRequestBody().readAllBytes();

                byte[] body = (providerStatus == 200 ? ROUTE_JSON : "{}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/geo+json");
                exchange.sendResponseHeaders(providerStatus, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void routing(DynamicPropertyRegistry registry) {
        registry.add("screenbook.routing.api-key", () -> "test-key");
        registry.add("screenbook.routing.base-url",
                () -> "http://localhost:" + provider.getAddress().getPort() + "/v2");
    }

    @AfterAll
    static void stopProvider() {
        provider.stop(0);
    }

    @Autowired
    RouteController controller;

    @Autowired
    CinemaQuery cinemas;

    @Autowired
    CacheManager caches;

    @BeforeEach
    void reset() {
        hits.set(0);
        providerStatus = 200;
        lastAuthorization = null;
        lastQuery = null;
        Objects.requireNonNull(caches.getCache("routes")).clear();
    }

    private long cinemaId() {
        return cinemas.nearest(LAT, LON, 1).get(0).id();
    }

    @Test
    @DisplayName("maps the provider's route and sends the key as a header, not in the URL")
    void mapsRoute() {
        RouteResponse route = controller.route(cinemaId(), LAT, LON);

        assertEquals(612, route.distanceMetres());
        assertEquals(132, route.durationSeconds());
        assertEquals(2, route.coordinates().length);
        assertEquals("test-key", lastAuthorization);
        assertNull(lastQuery);
    }

    @Test
    @DisplayName("origins in the same snapped cell share one provider call")
    void sameCellIsCached() {
        long id = cinemaId();

        controller.route(id, 53.4084, -2.9916);
        controller.route(id, 53.40844, -2.99158);
        assertEquals(1, hits.get());

        controller.route(id, 53.4094, -2.9916);
        assertEquals(2, hits.get());
    }

    @Test
    @DisplayName("a provider failure is a 503 and is not cached")
    void failureIsNotCached() {
        long id = cinemaId();

        providerStatus = 500;
        assertThrows(RoutingUnavailableException.class, () -> controller.route(id, LAT, LON));

        providerStatus = 200;
        RouteResponse route = controller.route(id, LAT, LON);

        assertEquals(612, route.distanceMetres());
        assertEquals(2, hits.get());
    }

    @Test
    @DisplayName("an unknown cinema is a 404 and never reaches the provider")
    void unknownCinema() {
        assertThrows(NotFoundException.class, () -> controller.route(-1, LAT, LON));
        assertEquals(0, hits.get());
    }

    @Test
    @DisplayName("a latitude off the planet is a 422 and never reaches the provider")
    void invalidPoint() {
        long id = cinemaId();

        assertThrows(ValidationFailedException.class, () -> controller.route(id, 91, LON));
        assertEquals(0, hits.get());
    }
}