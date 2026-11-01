package com.zonepilot.backend.exception;

/**
 * Replay/quota refusal. Mapped to 429, not 400: the request is valid,
 * the caller is just too frequent.
 */
public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) {
        super(message);
    }
}
