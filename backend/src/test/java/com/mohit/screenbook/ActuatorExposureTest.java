package com.mohit.screenbook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * SecurityConfig ends in permitAll, so anything exposed here is public.
 * Production exposes health and info only. The value is read from
 * application.properties and forced into the context, so a variable set in
 * the shell for local metrics cannot change what this checks.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
class ActuatorExposureTest {

    private static final String EXPOSURE = "management.endpoints.web.exposure.include";

    @DynamicPropertySource
    static void productionExposure(DynamicPropertyRegistry registry) throws IOException {
        Properties main = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application.properties"));
        registry.add(EXPOSURE, () -> main.getProperty(EXPOSURE));
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("production exposes health and nothing that reveals metrics")
    void metricsAreNotPublic() throws Exception {
        /* The control first, so a 404 below cannot mean the actuator is off. */
        assertEquals(200, status("/actuator/health"));

        assertEquals(404, status("/actuator/prometheus"));
        assertEquals(404, status("/actuator/metrics"));
    }

    private int status(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}