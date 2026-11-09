package com.mohit.screenbook.auth;

import com.mohit.screenbook.common.Emails;
import com.mohit.screenbook.mail.Mailer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class PasswordResetService {

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final PasswordResetTokens tokens;
    private final RefreshTokens refreshTokens;
    private final Mailer mailer;
    private final String linkBase;

    PasswordResetService(AppUserRepository users, PasswordEncoder passwords,
                         PasswordResetTokens tokens, RefreshTokens refreshTokens,
                         Mailer mailer,
                         @Value("${screenbook.reset.link-base}") String linkBase) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.mailer = mailer;
        this.linkBase = linkBase;
    }

    /**
     * Starts a reset, and tells the caller nothing.
     *
     * Returns void on purpose. An unknown address, a rate-limited one and a
     * real one are indistinguishable from outside, because a different answer
     * for any of them turns this endpoint into a way to test which addresses
     * are registered. Signup has to leak that to be usable; this does not.
     *
     * The token is url-safe Base64 with no padding, so it goes into the query
     * string as-is with nothing to encode.
     *
     * Not @Transactional, deliberately. The mail has to go out after the token
     * is committed, not inside the transaction that writes it: otherwise a
     * rollback leaves somebody holding a link for a token that no longer
     * exists. issue() is atomic by itself and the only other statement here is
     * one read, so there is nothing for an outer transaction to protect.
     */
    public void request(String rawEmail) {
        String email = Emails.normalise(rawEmail);

        users.findByEmail(email).ifPresent(user -> {
            Optional<String> token = tokens.issue(user.getId());
            token.ifPresent(raw ->
                    mailer.sendPasswordReset(user.getEmail(), "%s?token=%s".formatted(linkBase, raw)));
        });
    }

    /**
     * Spends the token, sets the password, and ends every session.
     *
     * Revoking is not optional. A password change that leaves the old sessions
     * alive is not a password change: whoever prompted the reset is still
     * signed in everywhere they already were. revokeAll commits in its own
     * transaction, which is what makes it survive anything that throws after
     * this point.
     *
     * Does not sign the user in. They have just chosen a password and proving
     * they can type it is a second of friction against the chance that the
     * link was opened by the wrong person on a shared machine.
     */
    @Transactional
    public void confirm(ResetPasswordRequest request) {
        UUID userId = tokens.spend(request.token());

        AppUser user = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "Reset token references a user that does not exist"));

        user.changePassword(passwords.encode(request.password()));
        users.saveAndFlush(user);

        refreshTokens.revokeAll(userId);
    }
}