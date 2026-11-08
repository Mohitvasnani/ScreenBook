package com.mohit.screenbook.booking;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    /**
     * Two dependencies rather than one facade, because the POST and the GET
     * are different concerns. Retry safety belongs to the write; a read of a
     * booking that already exists has nothing to be idempotent about.
     */
    private final IdempotentBookings idempotentBookings;
    private final BookingService bookings;

    BookingController(IdempotentBookings idempotentBookings, BookingService bookings) {
        this.idempotentBookings = idempotentBookings;
        this.bookings = bookings;
    }

    /**
     * Idempotency-Key is optional, so a request without one behaves as it
     * always did. The Jwt is null for a guest, which is what makes this a
     * guest booking rather than rejecting it.
     */
    @PostMapping
    public ResponseEntity<BookingResponse> create(
            @Valid @RequestBody CreateBookingRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {

        UUID userId = jwt == null ? null : UUID.fromString(jwt.getSubject());

        BookingResponse booking = idempotentBookings.confirm(
                request.holdId(), idempotencyKey, userId, request.email());

        return ResponseEntity
                .created(URI.create("/api/bookings/" + booking.bookingReference()))
                .body(booking);
    }

    /**
     * Public, and deliberately so. The reference is the ticket: a guest has
     * nothing else, and the URL is meant to be reloadable and shareable.
     * Guessing one means guessing six base32 characters.
     */
    @GetMapping("/{reference}")
    public BookingResponse get(@PathVariable String reference) {
        return bookings.find(reference);
    }
}
