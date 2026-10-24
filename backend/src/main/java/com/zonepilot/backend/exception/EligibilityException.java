package com.zonepilot.backend.exception;

/**
 * Eligibility-gate refusal with a plain-language reason for the dispatcher.
 * Mapped to 422, not 400: the request is well-formed, the assignment is not allowed.
 */
public class EligibilityException extends RuntimeException {

    public EligibilityException(String message) {
        super(message);
    }
}
