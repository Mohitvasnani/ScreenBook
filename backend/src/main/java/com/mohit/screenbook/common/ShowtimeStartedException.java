package com.mohit.screenbook.common;

import org.springframework.http.HttpStatus;

/**
 * 409 rather than 422, because the request was valid when the page was loaded
 * and the world moved on. Same family as SeatsUnavailableException: the client
 * should refresh rather than correct anything.
 */
public class ShowtimeStartedException extends ApiException {

    public ShowtimeStartedException() {
        super(HttpStatus.CONFLICT, Problems.SHOWTIME_STARTED, "Showtime started",
                "That screening has already started and can no longer be booked.");
    }
}