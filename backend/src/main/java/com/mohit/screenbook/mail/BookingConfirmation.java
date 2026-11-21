package com.mohit.screenbook.mail;

import java.time.ZonedDateTime;
import java.util.List;

/** What a confirmation says, as data. The mailer owns the wording. */
public record BookingConfirmation(
        String reference,
        String link,
        String filmTitle,
        String cinemaName,
        ZonedDateTime startsAt,
        List<String> seats,
        int totalMinor,
        String currency
) {
}