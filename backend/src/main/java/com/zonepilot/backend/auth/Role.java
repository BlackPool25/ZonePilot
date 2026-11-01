package com.zonepilot.backend.auth;

import com.zonepilot.backend.exception.ValidationException;

public enum Role {
    OWNER,
    DISPATCHER,
    DRIVER,
    /** Read-only demo login: short-lived token, GET endpoints only. */
    DEMO;

    /** Parses a client-supplied role name; unknown/null is a 400, never a 500. */
    public static Role from(String name) {
        if (name == null) {
            throw new ValidationException("Role is required");
        }
        try {
            return Role.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("unknown role '" + name + "'");
        }
    }
}
