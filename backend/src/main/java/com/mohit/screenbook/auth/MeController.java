package com.mohit.screenbook.auth;

import com.mohit.screenbook.booking.BookingResponse;
import com.mohit.screenbook.booking.BookingService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Everything under /api/me requires a credential, so the Jwt here is never
 * null: an unauthenticated request is rejected before it reaches this class.
 *
 * The subject is the user id and nothing else is read from the token. The
 * profile comes from the database, so a changed display name is correct
 * immediately rather than fifteen minutes later when the token expires.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final AuthService auth;
    private final BookingService bookings;

    MeController(AuthService auth, BookingService bookings) {
        this.auth = auth;
        this.bookings = bookings;
    }

    @GetMapping
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return auth.me(userId(jwt));
    }

    /** Newest first. Guest bookings never appear here, even same-email ones. */
    @GetMapping("/bookings")
    public List<BookingResponse> myBookings(@AuthenticationPrincipal Jwt jwt) {
        return bookings.forUser(userId(jwt));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
