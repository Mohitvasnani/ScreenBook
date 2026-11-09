package com.mohit.screenbook.common;

/**
 * The problem type URIs from docs/api-contract.md. Clients branch on the last
 * segment of these rather than on the prose in "detail", which is why each
 * case needs a stable identifier of its own.
 */
public final class Problems {

    public static final String BASE = "https://screenbook.dev/problems";

    public static final String NOT_FOUND = BASE + "/not-found";
    public static final String SEATS_UNAVAILABLE = BASE + "/seats-unavailable";
    public static final String HOLD_EXPIRED = BASE + "/hold-expired";
    public static final String HOLD_ALREADY_CONVERTED = BASE + "/hold-already-converted";
    public static final String VALIDATION_FAILED = BASE + "/validation-failed";

    public static final String IDEMPOTENCY_KEY_REUSED = BASE + "/idempotency-key-reused";
    public static final String IDEMPOTENCY_KEY_IN_PROGRESS = BASE + "/idempotency-key-in-progress";

    public static final String INVALID_CREDENTIALS = BASE + "/invalid-credentials";
    public static final String EMAIL_TAKEN = BASE + "/email-taken";
    public static final String INVALID_REFRESH_TOKEN = BASE + "/invalid-refresh-token";
    public static final String SHOWTIME_STARTED = BASE + "/showtime-started";
    private Problems() {
    }
}
