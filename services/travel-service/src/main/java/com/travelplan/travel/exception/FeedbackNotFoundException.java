package com.travelplan.travel.exception;

/** No active feedback matches the request (absent or already removed) — maps to 404. */
public class FeedbackNotFoundException extends RuntimeException {

    public FeedbackNotFoundException() {
        super("Feedback not found");
    }
}
