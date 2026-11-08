package com.mohit.screenbook.booking;

import com.mohit.screenbook.common.Emails;
import com.mohit.screenbook.common.IdempotencyKeyInProgressException;
import com.mohit.screenbook.common.IdempotencyKeyReusedException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Makes POST /api/bookings safe to retry.
 *
 * Deliberately NOT @Transactional. The claim must commit before the booking
 * starts, which cannot happen if both are in one transaction.
 *
 * Writes only. Reading a booking needs none of this, so the read paths go
 * straight to BookingService rather than through here.
 */
@Service
public class IdempotentBookings {

    private final BookingService bookings;
    private final IdempotencyStore keys;

    IdempotentBookings(BookingService bookings, IdempotencyStore keys) {
        this.bookings = bookings;
        this.keys = keys;
    }

    public BookingResponse confirm(UUID holdId, String idempotencyKey, UUID userId, String email) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return bookings.confirm(holdId, userId, email);
        }

        String hash = requestHash(holdId, userId, email);

        /* Claim first. Losing this race means another request already owns the
           key, and the only question is what it did with it. */
        if (!keys.claim(idempotencyKey, hash)) {
            IdempotencyStore.Claim existing = keys.find(idempotencyKey)
                    .orElseThrow(IdempotencyKeyInProgressException::new);

            if (!existing.requestHash().equals(hash)) {
                throw new IdempotencyKeyReusedException();
            }
            if (!existing.finished()) {
                throw new IdempotencyKeyInProgressException();
            }
            return bookings.find(existing.bookingReference());
        }

        try {
            BookingResponse booking = bookings.confirm(holdId, userId, email);
            keys.complete(idempotencyKey, booking.bookingReference());
            return booking;
        } catch (RuntimeException e) {
            keys.release(idempotencyKey);
            throw e;
        }
    }

    /**
     * Covers everything that changes the outcome, not just the hold. The same
     * key used for the same hold but a different account would otherwise
     * replay a booking attributed to someone else, which is exactly the
     * mistake the reuse check exists to catch.
     *
     * The first two fields are a UUID or empty and a UUID contains no pipe,
     * so the first two separators are unambiguous and everything after the
     * second one is the address. Absent fields are empty rather than the word
     * null, so the string says what it means.
     *
     * Emails.normalise, the same call the entity makes, so the hashed value
     * and the stored value cannot disagree.
     */
    private static String requestHash(UUID holdId, UUID userId, String email) {
        String canonical = holdId
                + "|" + Objects.toString(userId, "")
                + "|" + Objects.toString(Emails.normalise(email), "");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
