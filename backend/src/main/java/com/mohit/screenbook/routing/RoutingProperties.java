package com.mohit.screenbook.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "screenbook.routing")
public record RoutingProperties(String apiKey, String baseUrl) {
}