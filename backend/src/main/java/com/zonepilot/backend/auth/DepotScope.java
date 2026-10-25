package com.zonepilot.backend.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Publishes the authenticated caller identity from the SecurityContext (from JWT,
 * never from client params). The old Hibernate {@code depotFilter} is gone with
 * the single-fleet model; group scoping lives in {@link GroupScope}.
 */
@Component
public class DepotScope {

    public static DepotPrincipal current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof DepotPrincipal p) {
            return p;
        }
        return null;
    }
}
