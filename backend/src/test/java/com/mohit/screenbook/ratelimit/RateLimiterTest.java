package com.mohit.screenbook.ratelimit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Against a real Redis, because the claim is about what a Lua script does
 * inside Redis, and a mock would only prove the mock.
 *
 * Every test uses its own key, so the container can be shared across the class
 * without one test's tokens leaking into another.
 */
@Testcontainers
class RateLimiterTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static RateLimiter limiter;
    static final SimpleMeterRegistry METERS = new SimpleMeterRegistry();

    @BeforeAll
    static void connect() {
        limiter = limiterOn(REDIS.getHost(), REDIS.getMappedPort(6379));
    }

    /*
     * shareNativeConnection is off so every call gets its own connection.
     * Left on, all threads would queue their commands down one connection and
     * the concurrency test could pass even against a limiter that was not
     * atomic. With separate connections, commands really do arrive together.
     */
    private static RateLimiter limiterOn(String host, int port) {
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2))
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(host, port), client);
        factory.setShareNativeConnection(false);
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return new RateLimiter(template, METERS);
    }

    private static String key() {
        return "test:" + UUID.randomUUID();
    }


    private static double count(String outcome) {
        return METERS.get("screenbook.ratelimit.decisions")
                .tag("outcome", outcome).counter().count();
    }

    @Test
    @DisplayName("allows a burst up to capacity, then denies with a retry time")
    void allowsCapacityThenDenies() {
        String key = key();
        // 0.1 a second: after the bucket empties, the next token is about ten seconds away.
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire(key, 3, 0.1).allowed(), "request " + (i + 1));
        }
        RateLimiter.Decision denied = limiter.tryAcquire(key, 3, 0.1);
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterSeconds() >= 1 && denied.retryAfterSeconds() <= 10,
                "retry after was " + denied.retryAfterSeconds());
    }


    @Test
    @DisplayName("every decision is counted by outcome")
    void countsDecisions() {
        String key = key();
        double allowedBefore = count("allowed");
        double deniedBefore = count("denied");

        limiter.tryAcquire(key, 1, 0.001);
        limiter.tryAcquire(key, 1, 0.001);

        assertEquals(1, count("allowed") - allowedBefore);
        assertEquals(1, count("denied") - deniedBefore);
    }

    @Test
    @DisplayName("a request let through because Redis is down is counted as unavailable")
    void countsFailOpen() {
        double before = count("unavailable");

        assertTrue(limiterOn("localhost", 1).tryAcquire(key(), 1, 1).allowed());

        assertEquals(1, count("unavailable") - before);
    }

    @Test
    @DisplayName("a token comes back after the refill time")
    void refillsOverTime() throws Exception {
        String key = key();
        // Five a second, so one token every 200 ms.
        assertTrue(limiter.tryAcquire(key, 1, 5).allowed());
        assertFalse(limiter.tryAcquire(key, 1, 5).allowed());
        Thread.sleep(300);
        assertTrue(limiter.tryAcquire(key, 1, 5).allowed());
    }

    @Test
    @DisplayName("callers have independent buckets")
    void keysAreIndependent() {
        String a = key();
        String b = key();
        assertTrue(limiter.tryAcquire(a, 1, 0.1).allowed());
        assertFalse(limiter.tryAcquire(a, 1, 0.1).allowed());
        assertTrue(limiter.tryAcquire(b, 1, 0.1).allowed());
    }

    @Test
    @DisplayName("fifty simultaneous callers on a bucket of five: exactly five get in")
    void concurrentCallersCannotExceedCapacity() throws Exception {
        int callers = 50;
        String key = key();
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CyclicBarrier start = new CyclicBarrier(callers);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    // A refill this slow adds nothing measurable during the test.
                    return limiter.tryAcquire(key, 5, 0.001).allowed();
                }));
            }
            long allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            assertEquals(5, allowed);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("an unreachable Redis lets the request through")
    void failsOpen() {
        // Nothing listens on port 1, so the connection is refused at once.
        RateLimiter broken = limiterOn("localhost", 1);
        assertTrue(broken.tryAcquire(key(), 1, 1).allowed());
    }

    @Test
    @DisplayName("nonsense limits are a bug in the caller, not a request to allow")
    void rejectsNonsenseLimits() {
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(key(), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(key(), 1, 0));
    }
}