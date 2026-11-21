package com.mohit.screenbook.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The limits, so they can differ per environment without a code change. */
@ConfigurationProperties(prefix = "screenbook.ratelimit")
public record RateLimitProperties(Bucket routing) {

    public record Bucket(int capacity, double refillPerSecond) {
    }
}