package com.mohit.screenbook.mail;

/**
 * Sending email, behind one method.
 *
 * Named per message rather than a generic send(to, subject, body), so the copy
 * and the template live with the thing that knows how to send them and the
 * caller stays a flow rather than a letter writer.
 *
 * The interface exists so the whole reset flow is testable with no network. The
 * suite already needs Docker and nothing else, and a test that reaches a real
 * mail API is a test that fails when somebody else's service is down.
 */
public interface Mailer {

    void sendPasswordReset(String toEmail, String resetLink);
}