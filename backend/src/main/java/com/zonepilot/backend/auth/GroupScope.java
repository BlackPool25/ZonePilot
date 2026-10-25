package com.zonepilot.backend.auth;

import com.zonepilot.backend.entity.FleetGroup;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Manager ownership for fleet groups. OWNER sees all groups;
 * a signed-in DISPATCHER sees only groups they manage
 * (DRIVER logins own no groups, so they see none).
 * Absent auth (background jobs) is unrestricted, mirroring DepotScope.
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
        if (isOwner()) {
            return;
        }
        Long uid = currentUserId()
                .orElseThrow(() -> new AccessDeniedException("Login required"));
        if (group.getManager() == null || !group.getManager().getId().equals(uid)) {
            throw new AccessDeniedException("Not your group");
        }
    }
}
