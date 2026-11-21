package com.mohit.screenbook.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A token bucket per caller, kept in Redis.
 *
 * Each caller has a bucket that holds up to `capacity` tokens and refills at
 * `refillPerSecond`. A request takes one token. An empty bucket means no.
 * Capacity is how big a burst is tolerated, the refill rate is the sustained
 * rate, which is exactly the shape of a person clicking through a few routes.
 *
 * The whole read, refill, take and write runs as one Lua script. Redis runs a
 * script start to finish with nothing interleaved, so two simultaneous requests
 * cannot both read the same token count and both be let through, and a crash
 * cannot leave a half-written bucket.
 *
 * Fails open. If Redis cannot be reached the request is allowed and a warning
 * is logged: losing rate limiting for a while is a better failure than losing
 * bookings.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /*
     * KEYS[1] is the bucket. ARGV[1] is capacity, ARGV[2] is tokens per second.
     * Returns 0 if allowed, otherwise the whole seconds until a token exists.
     *
     * Time comes from Redis itself, not from the app, so two app instances can
     * never disagree about the clock. A missing bucket is a full one, which is
     * also why the key can safely expire once it would have refilled anyway.
     */
    private static final String TOKEN_BUCKET = """
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])

            local t = redis.call('TIME')
            local now = tonumber(t[1]) + tonumber(t[2]) / 1000000

            local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(bucket[1])
            local ts = tonumber(bucket[2])
            if tokens == nil then
              tokens = capacity
              ts = now
            end

            tokens = math.min(capacity, tokens + (now - ts) * refill)

            local retry = 0
            if tokens >= 1 then
              tokens = tokens - 1
            else
              retry = math.ceil((1 - tokens) / refill)
            end

            redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
            redis.call('EXPIRE', KEYS[1], math.ceil(capacity / refill))
            return retry
            """;

    private static final DefaultRedisScript<Long> SCRIPT =
            new DefaultRedisScript<>(TOKEN_BUCKET, Long.class);

    private final StringRedisTemplate redis;

    RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** What the limiter decided. retryAfterSeconds is 0 when allowed. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision PERMITTED = new Decision(true, 0);

        static Decision denied(long retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds);
        }
    }

    public Decision tryAcquire(String key, int capacity, double refillPerSecond) {
        /* A refill of zero would divide by zero in the script, and a capacity
           below one could never allow anything. Both are bugs in the caller. */
        if (capacity < 1 || refillPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "capacity must be at least 1 and refillPerSecond above 0");
        }
        try {
            Long retryAfter = redis.execute(SCRIPT, List.of(key),
                    Integer.toString(capacity), Double.toString(refillPerSecond));
            if (retryAfter == null || retryAfter == 0) {
                return Decision.PERMITTED;
            }
            return Decision.denied(retryAfter);
        } catch (DataAccessException e) {
            log.warn("Rate limiter unavailable, allowing the request: {}", e.getMessage());
            return Decision.PERMITTED;
        }
    }
}