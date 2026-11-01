package com.zonepilot.backend.auth;

/** Authenticated caller identity carried in the SecurityContext (from JWT, never from client params). */
public record DepotPrincipal(String username, Long depotId, Role role) {

    public boolean isOwner() {
        return role == Role.OWNER;
    }
}
