package com.mohit.screenbook.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
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
        Map<String, Object> body = Map.of(
                "sender", Map.of("name", senderName, "email", senderEmail),
                "to", List.of(Map.of("email", toEmail)),
                "subject", "Reset your ScreenBook password",
                "textContent", text(resetLink));

        /* Any failure propagates, and PasswordResetService is what swallows it.
           Keeping that decision there rather than here means the transport
           stays honest about whether it worked, which is also what makes this
           usable from anywhere else later. */
        Map<?, ?> response = http.post()
                .body(body)
                .retrieve()
                .body(Map.class);

        /* Brevo returns a messageId, which is the only handle on the message
           afterwards. Logged without the recipient: this line fires only for
           addresses that exist, so including it would put a list of real users
           in the logs. */
        log.info("Password reset mail accepted by Brevo, messageId {}",
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
}