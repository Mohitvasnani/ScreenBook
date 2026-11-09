package com.mohit.screenbook;

import com.mohit.screenbook.auth.AuthService;
import com.mohit.screenbook.auth.ForgotPasswordRequest;
import com.mohit.screenbook.auth.LoginRequest;
import com.mohit.screenbook.auth.PasswordResetService;
import com.mohit.screenbook.auth.ResetPasswordRequest;
import com.mohit.screenbook.auth.SignupRequest;
import com.mohit.screenbook.common.InvalidCredentialsException;
import com.mohit.screenbook.common.InvalidRefreshTokenException;
import com.mohit.screenbook.common.InvalidResetTokenException;
import com.mohit.screenbook.mail.Mailer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Password reset, end to end, with no mail server.
 *
 * The Mailer is swapped for one that records, which is the whole reason that
 * interface exists: every security property below is about what the flow does,
 * and none of them need an email to actually arrive.
 *
 * Not @Transactional, like the other service-level tests: these call services
 * that open their own transactions, and a rollback at the end would hide
 * whether anything was written.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class PasswordResetTest {

    private static final String OLD_PASSWORD = "correct horse battery";
    private static final String NEW_PASSWORD = "staple grinning harbour";

    /** Records instead of sending, and hands back the token from the link. */
    static class RecordingMailer implements Mailer {
        final List<String> links = new ArrayList<>();

        @Override
        public void sendPasswordReset(String toEmail, String resetLink) {
            links.add(resetLink);
        }

        /* The token is url-safe Base64 with no padding, so it needs no
           decoding: everything after token= is the token. */
        String lastToken() {
            String link = links.getLast();
            return link.substring(link.indexOf("token=") + "token=".length());
        }
    }

    @TestConfiguration
    static class Mail {
        @Bean
        @Primary
        RecordingMailer recordingMailer() {
            return new RecordingMailer();
        }
    }

    @Autowired
    AuthService auth;

    @Autowired
    PasswordResetService reset;

    @Autowired
    RecordingMailer mailer;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clearMail() {
        mailer.links.clear();
    }

    @Test
    @DisplayName("a reset link sets a new password and retires the old one")
    void resetChangesThePassword() {
        String email = freshEmail();
        auth.signUp(new SignupRequest(email, OLD_PASSWORD, "Mo"));

        reset.request(email);
        assertEquals(1, mailer.links.size(), "a known address should be mailed");

        reset.confirm(new ResetPasswordRequest(mailer.lastToken(), NEW_PASSWORD));

        assertNotNull(auth.logIn(new LoginRequest(email, NEW_PASSWORD)),
                "the new password should work");
        assertThrows(InvalidCredentialsException.class,
                () -> auth.logIn(new LoginRequest(email, OLD_PASSWORD)),
                "the old password must stop working");
    }

    /**
     * The property that matters most here. A different answer for an unknown
     * address, in the response or in the time it takes, turns this endpoint
     * into a way to test which addresses are registered.
     */
    @Test
    @DisplayName("an unknown address is accepted and nothing is sent")
    void unknownAddressIsIndistinguishable() {
        reset.request(freshEmail());
        assertTrue(mailer.links.isEmpty(), "no mail for an address with no account");
    }

    @Test
    @DisplayName("a second request inside the window sends nothing")
    void rateLimited() {
        String email = freshEmail();
        auth.signUp(new SignupRequest(email, OLD_PASSWORD, "Mo"));

        reset.request(email);
        reset.request(email);

        /* The caller cannot tell these apart, which is the point: a "slow
           down" response would confirm the address exists. */
        assertEquals(1, mailer.links.size(),
                "the second request inside the interval should send no mail");
    }

    @Test
    @DisplayName("a token works once")
    void singleUse() {
        String email = freshEmail();
        auth.signUp(new SignupRequest(email, OLD_PASSWORD, "Mo"));
        reset.request(email);
        String token = mailer.lastToken();

        reset.confirm(new ResetPasswordRequest(token, NEW_PASSWORD));

        assertThrows(InvalidResetTokenException.class,
                () -> reset.confirm(new ResetPasswordRequest(token, "another password here")),
                "a spent token must be rejected");
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredIsRejected() {
        String email = freshEmail();
        auth.signUp(new SignupRequest(email, OLD_PASSWORD, "Mo"));
        reset.request(email);
        String token = mailer.lastToken();

        /* Aged in SQL rather than by moving the clock, because the Clock bean
           is shared with every other class in this context and shifting it
           would move the pricing rules' lead-time band too. */
        int aged = jdbc.sql("""
                        UPDATE password_reset_token
                           SET requested_at = requested_at - interval '2 hours',
                               expires_at   = expires_at   - interval '2 hours'
                         WHERE used_at IS NULL
                           AND user_id = (SELECT id FROM app_user WHERE email = :email)
                        """)
                .param("email", email)
                .update();
        assertTrue(aged > 0, "nothing was aged, so this test proves nothing");

        assertThrows(InvalidResetTokenException.class,
                () -> reset.confirm(new ResetPasswordRequest(token, NEW_PASSWORD)));
    }

    @Test
    @DisplayName("an unknown token is rejected the same way as an expired one")
    void unknownTokenIsRejected() {
        assertThrows(InvalidResetTokenException.class,
                () -> reset.confirm(new ResetPasswordRequest("not-a-real-token", NEW_PASSWORD)));
    }

    /**
     * A password change that leaves old sessions alive is not a password
     * change: whoever prompted the reset is still signed in everywhere they
     * already were.
     */
    @Test
    @DisplayName("a reset ends every existing session")
    void resetRevokesSessions() {
        String email = freshEmail();
        AuthService.Session session = auth.signUp(new SignupRequest(email, OLD_PASSWORD, "Mo"));

        /* Proves the session was live before the reset, so its rejection
           afterwards means something. */
        assertNotNull(auth.refresh(session.refreshToken()));

        reset.request(email);
        reset.confirm(new ResetPasswordRequest(mailer.lastToken(), NEW_PASSWORD));

        AuthService.Session stillHeld = auth.logIn(new LoginRequest(email, NEW_PASSWORD));
        assertThrows(InvalidRefreshTokenException.class,
                () -> auth.refresh(session.refreshToken()),
                "the pre-reset refresh token must be dead");
        assertNotNull(stillHeld, "and a fresh session should work");
    }

    /* A new address per test, so tests cannot collide on the unique index. */
    private static String freshEmail() {
        return "reset-%s@example.com".formatted(UUID.randomUUID());
    }
}