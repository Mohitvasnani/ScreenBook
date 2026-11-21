package com.mohit.screenbook.common;

import com.mohit.screenbook.pricing.PricingProperties;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.mohit.screenbook.catalogue.SeedProperties;
import java.time.Clock;
import com.mohit.screenbook.routing.RoutingProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableAsync;
/**
 * Application-wide beans and the OpenAPI document's metadata.
 */
@Configuration
@EnableCaching
@EnableAsync
@EnableConfigurationProperties({
        PricingProperties.class,
        SeedProperties.class,
        RoutingProperties.class
})
@OpenAPIDefinition(info = @Info(
        title = "ScreenBook API",
        version = "0.1.0",
        description = """
                Cinema seat booking. A seat goes AVAILABLE -> HELD -> BOOKED, \
                and two people claiming one seat at the same instant produce \
                exactly one 201 and one 409."""))
public class AppConfig {

    /**
     * Injecting a Clock rather than calling Instant.now() directly means the
     * expiry tests can move time instead of sleeping for five minutes.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}