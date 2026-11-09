package com.mohit.screenbook.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final SecretKey signingKey;

    SecurityConfig(@Value("${screenbook.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        /* HMAC-SHA256 needs a key at least as long as its output. A shorter one
           is accepted by some libraries and quietly weakens every token, so it
           fails here instead, at startup, where it is impossible to miss. */
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "screenbook.jwt.secret must be at least 32 bytes; got " + bytes.length);
        }
        this.signingKey = new SecretKeySpec(bytes, "HmacSHA256");
    }

    /**
     * Strength 10, not the 12 you often see. BCrypt is deliberately slow and
     * each step doubles the work: on Render's free tier 12 costs several
     * hundred milliseconds per login, which is latency on the one request a
     * user is already impatient about. 10 is the Spring default and remains
     * far beyond what a password cracker finds cheap.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey));
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(signingKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * The one custom piece. Spring Security's resource server reads bearer
     * tokens from the Authorization header; this points it at a cookie
     * instead, so the token is httpOnly and unreadable by JavaScript while
     * everything else, signature checking, expiry, the SecurityContext, is
     * still the framework's job rather than a hand-rolled filter.
     *
     * Returning null means "no credential presented", which leaves the request
     * anonymous. That is what lets guest checkout work.
     */
    @Bean
    BearerTokenResolver cookieTokenResolver() {
        return request -> {
            Cookie[] cookies = request.getCookies();
            if (cookies == null) return null;
            for (Cookie cookie : cookies) {
                if (AuthCookies.ACCESS.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
            return null;
        };
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, BearerTokenResolver resolver)
            throws Exception {

        /* Opts out of Spring's deferred CSRF token loading, which otherwise
           only materialises the token when something reads it. A separated
           frontend needs the cookie to exist from the first response. */
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                .authorizeHttpRequests(auth -> auth
                        /* The only endpoints that need a credential. Everything
                           else works signed in or not, which is what guest
                           checkout means. */
                        .requestMatchers("/api/me/**").authenticated()
                        .anyRequest().permitAll())

                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(resolver)
                        .jwt(Customizer.withDefaults()))

                .csrf(csrf -> csrf
                        /* withHttpOnlyFalse because the frontend has to read
                           this one and echo it back in a header. That is not a
                           weakness: a CSRF token is not a secret from the page,
                           only from other origins, which the same-origin policy
                           already enforces. */
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler)
                        .ignoringRequestMatchers(
                                /* Booking endpoints behave identically with or
                                   without a session, so there is nothing for an
                                   attacker to forge that they could not simply
                                   do themselves. Leaving them open also keeps
                                   Swagger UI and curl usable against a public
                                   demo API, which is a deliberate trade. */
                                "/api/showtimes/**", "/api/holds/**", "/api/bookings/**",   "/api/auth/signup", "/api/auth/login",
                                "/api/auth/forgot-password", "/api/auth/reset-password",
                                /* Credentials are in the body here, so there is
                                   no ambient authority to forge. The residual
                                   risk is login CSRF, forcing a victim into the
                                   attacker's account, which is real but minor
                                   on a site holding nothing worth planting.
                                   Refresh and logout stay protected, because
                                   those do ride on the cookie. */
                                "/api/auth/signup", "/api/auth/login"))

                /* No HttpSession anywhere. The access token carries the
                   identity and the refresh token row is the only server-side
                   state, which is what makes the service horizontally
                   scalable without sticky sessions. */
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                /* Both would add login pages and WWW-Authenticate prompts to an
                   API that answers in JSON. */
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());

        return http.build();
    }
}