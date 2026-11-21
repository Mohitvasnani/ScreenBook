package com.mohit.screenbook.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Brevo's transactional email API.
 *
 * Chosen over SendGrid for one reason: single sender verification, so a
 * verified address is enough and no DNS records or owned domain are needed.
 * That is the difference between shipping this today and waiting on a domain.
 *
 * textContent rather than htmlContent, which is what Brevo's own docs
 * recommend for messages like this. There is nothing to lay out, and plain
 * text has no markup to escape a token into.
 *
 * Selected only when screenbook.mail.provider is brevo. LoggingMailer is the
 * default, so a fresh clone needs no account anywhere.
 */
@Component
@ConditionalOnProperty(name = "screenbook.mail.provider", havingValue = "brevo")
public class BrevoMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(BrevoMailer.class);

    private static final String SEND_URL = "https://api.brevo.com/v3/smtp/email";

    /* VV prints the zone id, so the time is never ambiguous between
       the two markets. */
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm (VV)", Locale.UK);

    private final RestClient http;
    private final String senderEmail;
    private final String senderName;
    private final long ttlMinutes;

    BrevoMailer(RestClient.Builder builder,
                @Value("${screenbook.mail.brevo.api-key}") String apiKey,
                @Value("${screenbook.mail.brevo.sender-email}") String senderEmail,
                @Value("${screenbook.mail.brevo.sender-name}") String senderName,
                @Value("${screenbook.reset.ttl-minutes}") long ttlMinutes) {

        /* Checked here rather than left to fail at send time, because the first
           reset request would be the only thing that revealed it, and that
           failure is deliberately swallowed. A misconfigured provider should be
           loud at startup. */
        if (apiKey.isBlank() || senderEmail.isBlank()) {
            throw new IllegalStateException(
                    "BREVO_API_KEY and BREVO_SENDER_EMAIL are required when "
                            + "MAIL_PROVIDER is brevo");
        }

        /* The builder comes from Boot, so spring.http.client.* timeouts apply.
           The key goes on the builder rather than per call: there is one
           endpoint and it is needed every time.

           Note it must be a v3 API key from the API Keys tab. An SMTP key
           looks similar and is rejected. */
        this.http = builder
                .baseUrl(SEND_URL)
                .defaultHeader("api-key", apiKey)
                .defaultHeader("accept", "application/json")
                .build();

        this.senderEmail = senderEmail;
        this.senderName = senderName;
        this.ttlMinutes = ttlMinutes;
    }

    @Override
    public void sendPasswordReset(String toEmail, String resetLink) {
        send(toEmail, "Reset your ScreenBook password", text(resetLink), "Password reset");
    }

    @Override
    public void sendBookingConfirmation(String toEmail, BookingConfirmation booking) {
        send(toEmail, "Your ScreenBook booking " + booking.reference(),
                confirmationText(booking), "Booking confirmation");
    }

    /* Any failure propagates, and each caller decides what a failure means.
       Keeping that out of here is what leaves this transport honest about
       whether it worked. */
    private void send(String toEmail, String subject, String textContent, String kind) {
        Map<String, Object> body = Map.of(
                "sender", Map.of("name", senderName, "email", senderEmail),
                "to", List.of(Map.of("email", toEmail)),
                "subject", subject,
                "textContent", textContent);

        Map<?, ?> response = http.post()
                .body(body)
                .retrieve()
                .body(Map.class);

        /* Brevo returns a messageId, which is the only handle on the message
           afterwards. Logged without the recipient, so the logs do not
           become a list of real users. */
        log.info("{} mail accepted by Brevo, messageId {}", kind,
                response == null ? "unknown" : response.get("messageId"));
    }

    /* The lifetime comes from config rather than being written into the copy,
       for the same reason the lead-time rule builds its label from its
       threshold: a message that claims thirty minutes while the setting says
       ten is a lie nobody notices. */
    private String text(String resetLink) {
        return """
                Someone asked to reset the password on your ScreenBook account.

                Open this link to choose a new one. It works once and expires in \
                %d minutes:

                %s

                If that was not you, ignore this message. Nothing has changed and \
                your current password still works.
                """.formatted(Duration.ofMinutes(ttlMinutes).toMinutes(), resetLink);
    }

    private String confirmationText(BookingConfirmation b) {
        return """
                Your booking is confirmed.

                %s
                %s
                %s

                Seats: %s
                Total: %s

                Reference: %s
                This page is your ticket, so keep the link:
                %s
                """.formatted(
                b.filmTitle(), b.cinemaName(), WHEN.format(b.startsAt()),
                String.join(", ", b.seats()), money(b.totalMinor(), b.currency()),
                b.reference(), b.link());
    }

    /* Minor units to a plain amount, using the currency's own number of
       decimals. */
    private static String money(int minor, String currency) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        return currency + " " + BigDecimal.valueOf(minor, digits).toPlainString();
    }
}