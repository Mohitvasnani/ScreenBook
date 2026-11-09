package com.mohit.screenbook.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Prints the link instead of sending it. Selected by default, so a fresh clone
 * runs the whole reset flow with no account anywhere: you read the link off
 * the console.
 *
 * matchIfMissing = true is deliberate. The failure mode of defaulting to a
 * real provider is sending mail from a developer's laptop; the failure mode of
 * defaulting to this is a production deployment that quietly sends nothing,
 * which is why the property is listed in docs/environment.md rather than left
 * to be discovered.
 */
@Component
@ConditionalOnProperty(name = "screenbook.mail.provider", havingValue = "log",
        matchIfMissing = true)
public class LoggingMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailer.class);

    @Override
    public void sendPasswordReset(String toEmail, String resetLink) {
        log.info("Password reset for {}: {}", toEmail, resetLink);
    }
}