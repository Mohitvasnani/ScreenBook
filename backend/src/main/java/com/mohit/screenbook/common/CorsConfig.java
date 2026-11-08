package com.mohit.screenbook.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Not for this project's own frontend. That calls /api on its own origin,
 * because Next.js rewrites it to this service, so those requests are
 * same-origin and CORS never enters into them.
 *
 * This exists for the public API the README advertises: a browser client on
 * some other origin reading showtimes and seat maps, or booking as a guest.
 * Being signed in cross-origin is deliberately impossible, see
 * allowCredentials below.
 *
 * Origins come from configuration, not source, so nothing here changes
 * between environments.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    CorsConfig(@Value("${screenbook.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                /* Patterns rather than plain origins, so a Vercel preview
                   wildcard is possible later without changing this code. */
                .allowedOriginPatterns(allowedOrigins)
                /* Only what the client actually uses. A wildcard here would
                   advertise methods this API does not have. */
                .allowedMethods("GET", "POST", "DELETE")
                .allowedHeaders("Content-Type", "Accept")
                /* Cross-origin JavaScript cannot read Location unless it is
                   exposed, and POST /api/holds returns one. */
                .exposedHeaders("Location")
                /* No cookies, so no cross-origin session. A session cookie is
                   only ever first-party, through the rewrite. A hold id is the
                   capability for guest flows and it travels in the URL. */
                .allowCredentials(false)
                /* Cache the preflight, so the browser does not send an OPTIONS
                   before every single POST. */
                .maxAge(3600);
    }
}