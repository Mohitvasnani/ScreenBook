package com.mohit.screenbook.auth;

import com.mohit.screenbook.common.Hashes;
import com.mohit.screenbook.common.InvalidResetTokenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Reset links, issued and spent.
 *
 * Deliberately knows nothing about email. It mints a token and hands the raw
 * value back exactly once; who sends it, and how, is someone else's problem.
 * That is what lets the whole flow be tested without a mail server.
 */
@Repository
public class PasswordResetTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    /* 256 bits, which is why the stored hash can be a fast one. */
    private static final int TOKEN_BYTES = 32;

    private static final String INSERT = """
            INSERT INTO password_reset_token (id, user_id, token_hash, expires_at)
            VALUES (:id, :userId, :hash, :expiresAt)
            """;

    /* The rate limit, as a question the table can already answer. */
    private static final String REQUESTED_RECENTLY = """
            SELECT exists(
                SELECT 1 FROM password_reset_token
                 WHERE user_id      = :userId
                   AND requested_at > :since)
            """;

    /* Find, check and spend in one statement, so two simultaneous
       presentations produce one winner with no lock and no version. The second
       updates zero rows, gets an empty Optional, and is rejected.

       RETURNING is what makes this possible: without it this would be a read
       followed by a write, and the window between them is exactly the race. */
    private static final String SPEND = """
            UPDATE password_reset_token
               SET used_at = :now
             WHERE token_hash = :hash
               AND used_at    IS NULL
               AND expires_at > :now
            RETURNING user_id
            """;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration ttl;
    private final Duration minInterval;

    PasswordResetTokens(JdbcClient jdbc, Clock clock,
                        @Value("${screenbook.reset.ttl-minutes}") long ttlMinutes,
                        @Value("${screenbook.reset.min-request-interval-seconds}")
                        long minIntervalSeconds) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.minInterval = Duration.ofSeconds(minIntervalSeconds);
    }

    /**
     * A new token, or empty if this account asked too recently.
     *
     * Empty is not an error and the caller must not report it as one. The
     * endpoint answers 202 either way, because telling a caller "slow down"
     * confirms the address exists, which is the one thing the endpoint is built
     * not to reveal.
     */
    @Transactional
    public Optional<String> issue(UUID userId) {
        Instant now = clock.instant();

        if (requestedRecently(userId, now)) {
            return Optional.empty();
        }

        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        jdbc.sql(INSERT)
                .param("id", UUID.randomUUID())
                .param("userId", userId)
                .param("hash", Hashes.sha256(raw))
                /* OffsetDateTime, not Instant: the driver will not infer a SQL
                   type for an Instant, because an Instant carries no zone and
                   it declines to guess. */
                .param("expiresAt", now.plus(ttl).atOffset(ZoneOffset.UTC))
                .update();

        return Optional.of(raw);
    }

    /**
     * Spends a token and returns whose account it belongs to.
     *
     * Every rejection is the same exception, whether the token never existed,
     * expired, or was already used. A caller holding a dead link cannot tell
     * which, and does not need to: the answer is the same, ask for another.
     */
    @Transactional
    public UUID spend(String rawToken) {
        return jdbc.sql(SPEND)
                .param("hash", Hashes.sha256(rawToken))
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .query(UUID.class)
                .optional()
                .orElseThrow(InvalidResetTokenException::new);
    }

    private boolean requestedRecently(UUID userId, Instant now) {
        return Boolean.TRUE.equals(jdbc.sql(REQUESTED_RECENTLY)
                .param("userId", userId)
                .param("since", now.minus(minInterval).atOffset(ZoneOffset.UTC))
                .query(Boolean.class)
                .single());
    }
}