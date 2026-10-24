package com.zonepilot.backend.auth;

public class BadTokenException extends RuntimeException {
    public BadTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
