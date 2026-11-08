package com.mohit.screenbook.auth;

import com.mohit.screenbook.common.EmailTakenException;
import com.mohit.screenbook.common.Emails;
import com.mohit.screenbook.common.InvalidCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class AuthService {

    /**
     * A real BCrypt hash of a value nobody knows, compared against when no
     * account matches. Without it the "no such user" path returns in
     * microseconds and the "wrong password" path takes the cost of a hash, and
     * the difference tells an attacker which addresses are registered.
     */
    private static final String ABSENT_USER_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMye1VdLLCRyRTtF6Uc8pZ0sWBLCJ9WVbfO";

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final AccessTokens accessTokens;
    private final RefreshTokens refreshTokens;
    private final Clock clock;

    AuthService(AppUserRepository users, PasswordEncoder passwords,
                AccessTokens accessTokens, RefreshTokens refreshTokens, Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    public record Session(AppUser user, String accessToken, String refreshToken) {
    }

    /**
     * Signing up signs you in. The alternative, redirecting to a login form
     * you just proved you can pass, is friction for nothing.
     */
    @Transactional
    public Session signUp(SignupRequest request) {
        String email = Emails.normalise(request.email());

        /* Checked rather than caught from the unique index, because the honest
           answer here is a 409 and not a 500. The index is still the guarantee
           if two signups race; this is the readable path. */
        if (users.findByEmail(email).isPresent()) {
            throw new EmailTakenException();
        }

        AppUser user = users.saveAndFlush(AppUser.of(
                email,
                passwords.encode(request.password()),
                request.displayName(),
                clock.instant()));

        return issue(user);
    }

    @Transactional
    public Session logIn(LoginRequest request) {
        String email = Emails.normalise(request.email());

        AppUser user = users.findByEmail(email).orElse(null);
        if (user == null) {
            /* Deliberately wasted work. See ABSENT_USER_HASH. */
            passwords.matches(request.password(), ABSENT_USER_HASH);
            throw new InvalidCredentialsException();
        }

        if (!passwords.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return issue(user);
    }

    /** Rotates the refresh token and mints a new access token alongside it. */
    @Transactional
    public Session refresh(String rawRefreshToken) {
        RefreshTokens.Rotation rotation = refreshTokens.rotate(rawRefreshToken);

        AppUser user = users.findById(rotation.userId())
                .orElseThrow(() -> new IllegalStateException(
                        "Refresh token references a user that does not exist"));

        return new Session(user, accessTokens.mint(user), rotation.nextToken());
    }

    @Transactional
    public void logOut(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokens.revoke(rawRefreshToken);
        }
        /* No token, nothing to revoke. Logging out when you are not logged in
           is not an error, the same way releasing a lapsed hold is not. */
    }

    @Transactional(readOnly = true)
    public MeResponse me(UUID userId) {
        AppUser user = users.findById(userId)
                .orElseThrow(InvalidCredentialsException::new);
        return new MeResponse(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }

    private Session issue(AppUser user) {
        return new Session(
                user, accessTokens.mint(user), refreshTokens.issue(user.getId()));
    }
}
