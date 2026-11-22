package com.mohit.screenbook;

import com.mohit.screenbook.catalogue.CinemaQuery;
import com.mohit.screenbook.common.RateLimitedException;
import com.mohit.screenbook.routing.RouteController;
import com.mohit.screenbook.routing.RouteResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * The route budget wired into RoutingService, against a real Redis and a fake
 * provider. RateLimiterTest already proves the bucket; this proves where the
 * service spends from it: only on a call that would reach the provider.
 *
 * The bucket holds two tokens and effectively never refills, so the third
 * distinct origin is refused every time. Origins 0.001 degrees apart land in
 * different snapped cells, and each cell is one provider call.
 */
@Import(TestcontainersConfiguration.class)
@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
class RoutingRateLimitTest {

    private static final double LON = -2.9916;
    private static final double LAT_A = 53.4084;
    private static final double LAT_B = 53.4094;
    private static final double LAT_C = 53.4104;

    /* RoutingService keeps this private. Repeated here to empty the bucket. */
    private static final String BUCKET_KEY = "rl:routing:provider";

    private static final String ROUTE_JSON = """
            {"features":[{"geometry":{"type":"LineString","coordinates":\
            [[-2.9916,53.4084],[-2.9864,53.4072]]},\
            "properties":{"summary":{"distance":612.4,"duration":131.6}}}]}""";

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private static final AtomicInteger hits = new AtomicInteger();

    private static final HttpServer provider = startProvider();

    private static HttpServer startProvider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                hits.incrementAndGet();
                exchange.getRequestBody().readAllBytes();

                byte[] body = ROUTE_JSON.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/geo+json");
                exchange.sendResponseHeaders(200, body.length);
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
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("screenbook.routing.api-key", () -> "test-key");
        registry.add("screenbook.routing.base-url",
                () -> "http://localhost:" + provider.getAddress().getPort() + "/v2");

        registry.add("spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        /* The app's 250 ms timeouts suit production. A cold first connection to
           a container can exceed them, and the limiter would then fail open and
           let a request through that this test expects to be refused. */
        registry.add("spring.data.redis.connect-timeout", () -> "2s");
        registry.add("spring.data.redis.timeout", () -> "2s");

        registry.add("screenbook.ratelimit.routing.capacity", () -> "2");
        registry.add("screenbook.ratelimit.routing.refill-per-second", () -> "0.001");
    }

    @AfterAll
    static void stopProvider() {
        provider.stop(0);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    RouteController controller;

    @Autowired
    CinemaQuery cinemas;

    @Autowired
    CacheManager caches;

    @Autowired
    StringRedisTemplate redis;

    private final HttpClient http = HttpClient.newHttpClient();

    /* The bucket lives in Redis and the routes in Caffeine, and both outlive a
       test, so each test starts with both empty. */
    @BeforeEach
    void reset() {
        hits.set(0);
        Objects.requireNonNull(caches.getCache("routes")).clear();
        redis.delete(BUCKET_KEY);
    }

    private long cinemaId() {
        return cinemas.nearest(LAT_A, LON, 1).get(0).id();
    }

    private HttpResponse<String> routeOver(long cinemaId, double lat) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/cinemas/" + cinemaId
                                + "/route?lat=" + lat + "&lon=" + LON))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("a spent budget is refused with a retry time and never reaches the provider")
    void spentBudgetIsRefused() {
        long id = cinemaId();

        controller.route(id, LAT_A, LON);
        controller.route(id, LAT_B, LON);
        assertEquals(2, hits.get());

        RateLimitedException refused = assertThrows(RateLimitedException.class,
                () -> controller.route(id, LAT_C, LON));

        assertTrue(refused.getRetryAfterSeconds() > 0);
        assertEquals(2, hits.get());
    }

    @Test
    @DisplayName("a cached route is still served after the budget is spent")
    void cacheHitCostsNothing() {
        long id = cinemaId();

        controller.route(id, LAT_A, LON);
        controller.route(id, LAT_B, LON);

        /* Without this the test would pass with no limiter at all, because a
           cache hit is free either way. It proves the budget really is spent. */
        assertThrows(RateLimitedException.class, () -> controller.route(id, LAT_C, LON));

        RouteResponse cached = controller.route(id, LAT_A, LON);

        assertEquals(612, cached.distanceMetres());
        assertEquals(2, hits.get());
    }

    @Test
    @DisplayName("over HTTP the refusal is a 429 with a Retry-After header")
    void refusalIsA429() throws Exception {
        long id = cinemaId();

        /* The two 200s first, so the 429 below can only be the limiter. */
        assertEquals(200, routeOver(id, LAT_A).statusCode());
        assertEquals(200, routeOver(id, LAT_B).statusCode());

        HttpResponse<String> refused = routeOver(id, LAT_C);

        assertEquals(429, refused.statusCode());
        long retryAfter = Long.parseLong(refused.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(retryAfter > 0);
        assertTrue(refused.body().contains("rate-limited"));
        assertEquals(2, hits.get());
    }
}
