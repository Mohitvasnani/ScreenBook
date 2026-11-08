package com.mohit.screenbook.common;

import java.util.Locale;

/**
 * One definition of what a canonical email address looks like.
 *
 * It exists because the rule had started to live in two places: Booking
 * trimmed and lowercased before storing, the idempotency hash only
 * lowercased. Two rules that agree on most input and disagree on the rest are
 * worse than one rule, because the disagreement surfaces as a legitimate
 * retry being reported as key reuse.
 *
 * Lowercasing is not tidiness. Both app_user.email and booking.email carry a
 * CHECK (email = lower(email)), so a value that skips this method is rejected
 * by Postgres rather than stored wrong.
 */
public final class Emails {

    private Emails() {
    }

    /**
     * Null for anything carrying no address, so "absent" has one
     * representation rather than three: null, "", and "   ".
     *
     * Locale.ROOT rather than the default locale. String.toLowerCase() with no
     * argument uses the JVM's default, and under a Turkish locale an I becomes
     * a dotless i, so the same address would normalise differently depending
     * on how the machine running it happens to be configured.
     */
    public static String normalise(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
