package com.zonepilot.backend.auth;

import com.zonepilot.backend.entity.FleetGroup;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Manager ownership for fleet groups. OWNER sees all groups;
 * a signed-in DISPATCHER sees only groups they manage
 * (DRIVER logins own no groups, so they see none).
 * Null/absent principal is denied ("Login required") — there is
 * no unrestricted path; background jobs must run with auth context.
 */
@Component
public class GroupScope {

    private final AppUserRepository users;

    public GroupScope(AppUserRepository users) {
        this.users = users;
    }

    public boolean isOwner() {
        DepotPrincipal p = DepotScope.current();
        return p != null && p.isOwner();
    }

    public Optional<Long> currentUserId() {
        DepotPrincipal p = DepotScope.current();
        if (p == null) {
            return Optional.empty();
        }
        return users.findByEmail(p.username()).map(AppUser::getId);
    }

    public Optional<AppUser> currentUser() {
        DepotPrincipal p = DepotScope.current();
        if (p == null) {
            return Optional.empty();
        }
        return users.findByEmail(p.username());
    }

    public void requireGroupAccess(FleetGroup group) {
        requireGroupAccess(DepotScope.current(), group);
    }

    /** Principal-explicit variant for the WS channel (no SecurityContext there). */
    public void requireGroupAccess(DepotPrincipal principal, FleetGroup group) {
        if (principal != null && principal.isOwner()) {
            return;
        }
        Long uid = principal == null ? null : users.findByEmail(principal.username())
                .map(AppUser::getId).orElse(null);
        if (uid == null) {
            throw new AccessDeniedException("Login required");
        }
        if (group.getManager() == null || !group.getManager().getId().equals(uid)) {
            throw new AccessDeniedException("Not your group");
        }
    }
}
