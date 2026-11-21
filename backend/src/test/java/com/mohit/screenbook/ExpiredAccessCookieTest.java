package com.mohit.screenbook;

import com.mohit.screenbook.auth.AuthService;
import com.mohit.screenbook.auth.SignupRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * The access cookie outlives its token so that expiry is a 401 and not a
 * silent downgrade to guest. These pin what that costs: the expired token is
 * rejected everywhere, so refresh must not look at it, and a refresh that
 * fails must clear it.
 *
 * Real HTTP on a random port. The behaviour under test is the filter chain,
 * which a service-level call never enters.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
class ExpiredAccessCookieTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    AuthService auth;

    @Autowired
    JwtEncoder encoder;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("an expired access cookie is a 401 even on a public endpoint")
    void expiredTokenIsNotAnonymous() throws Exception {
        /* The control first, so the 401 below can only be the cookie. */
        assertEquals(200, get("/api/cinemas/cities", null).statusCode());
        assertEquals(401, get("/api/cinemas/cities", expiredAccessToken()).statusCode());
    }

    @Test
    @DisplayName("refresh works with an expired access cookie and reissues it for the refresh lifetime")
    void refreshIgnoresTheExpiredCookie() throws Exception {
        AuthService.Session session = auth.signUp(new SignupRequest(
                "expired-" + UUID.randomUUID() + "@example.com",
                "correct horse battery", "Test User"));

        HttpResponse<String> response = refresh(expiredAccessToken(), session.refreshToken());

        assertEquals(200, response.statusCode());
        assertTrue(setCookie(response, "sb_access").contains("Max-Age=2592000"),
                "the access cookie should last 30 days, the refresh lifetime");
    }

    @Test
    @DisplayName("a refresh that fails clears both cookies")
    void failedRefreshClearsTheSession() throws Exception {
        HttpResponse<String> response = refresh(expiredAccessToken(), "not-a-real-token");

        assertEquals(401, response.statusCode());
        /* The controller's own answer, not the filter's. */
        assertTrue(response.body().contains("invalid-refresh-token"), response.body());
        assertTrue(setCookie(response, "sb_access").contains("Max-Age=0"));
        assertTrue(setCookie(response, "sb_refresh").contains("Max-Age=0"));
    }

    private String expiredAccessToken() {
        Instant issued = Instant.now().minus(1, ChronoUnit.HOURS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("screenbook")
                .issuedAt(issued)
                .expiresAt(issued.plus(15, ChronoUnit.MINUTES))
                .subject(UUID.randomUUID().toString())
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private HttpResponse<String> get(String path, String accessToken) throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (accessToken != null) {
            request.header("Cookie", "sb_access=" + accessToken);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /* Refresh is CSRF protected, so the token goes in a cookie and a header. */
    private HttpResponse<String> refresh(String accessToken, String refreshToken) throws Exception {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + "/api/auth/refresh"))
                .header("Cookie", "sb_access=" + accessToken + "; sb_refresh=" + refreshToken
                        + "; XSRF-TOKEN=t")
                .header("X-XSRF-TOKEN", "t")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String setCookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(name + "="))
                .findFirst()
                .orElse("");
    }
}