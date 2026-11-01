package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.DepotScope;
import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.dto.response.SosAlertResponse;
import com.zonepilot.backend.entity.SosAlert;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.repository.GroupDriverRepository;
import com.zonepilot.backend.repository.SosAlertRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Dispatcher SOS inbox: newest first with unknown/stale flags for the UI warning. */
@Service
public class SosService {

    private final SosAlertRepository sosAlerts;
    private final GroupScope scope;
    private final AppUserRepository users;
    private final FleetGroupRepository groups;
    private final GroupDriverRepository groupDrivers;
    private final AuditService audit;

    public SosService(SosAlertRepository sosAlerts) {
        this(sosAlerts, null, null, null, null, AuditService.noop());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SosService(SosAlertRepository sosAlerts,
                      GroupScope scope,
                      AppUserRepository users, FleetGroupRepository groups,
                      GroupDriverRepository groupDrivers, AuditService audit) {
        this.sosAlerts = sosAlerts;
        this.scope = scope;
        this.users = users;
        this.groups = groups;
        this.groupDrivers = groupDrivers;
        this.audit = audit == null ? AuditService.noop() : audit;
    }

    @Transactional(readOnly = true)
    public List<SosAlertResponse> listForDispatch() {
        DepotPrincipal p = DepotScope.current();
        if (p == null || (p.role() != Role.OWNER && p.role() != Role.DISPATCHER)) {
            throw new AccessDeniedException("Dispatcher login required");
        }
        List<SosAlert> all = sosAlerts.findAllByOrderByCreatedAtDesc();
        if (p.role() == Role.OWNER || scope == null) {
            return all.stream().map(SosAlertResponse::from).toList();
        }
        // Manager inbox: only alerts from tours in managed groups, or from
        // drivers rostered in managed groups (routeless alerts included).
        Set<Long> managed = managedGroupIds(p);
        return all.stream()
                .filter(a -> inManagedGroups(a, managed))
                .map(SosAlertResponse::from).toList();
    }

    private Set<Long> managedGroupIds(DepotPrincipal p) {
        if (users == null || groups == null) {
            return Set.of();
        }
        Long uid = users.findByEmail(p.username()).map(u -> u.getId()).orElse(null);
        if (uid == null) {
            return Set.of();
        }
        return groups.findByManager_IdOrderByCreatedAtDesc(uid).stream()
                .map(g -> g.getId()).collect(Collectors.toSet());
    }

    private boolean inManagedGroups(SosAlert alert, Set<Long> managed) {
        if (alert.getRoute() != null && alert.getRoute().getTour() != null
                && alert.getRoute().getTour().getGroup() != null
                && managed.contains(alert.getRoute().getTour().getGroup().getId())) {
            return true;
        }
        // Routeless alert: visible when its driver is rostered in a managed group.
        if (alert.getDriver() != null && groupDrivers != null) {
            for (Long gid : managed) {
                try {
                    if (groupDrivers.existsByGroupIdAndDriverId(gid, alert.getDriver().getId())) {
                        return true;
                    }
                } catch (RuntimeException ignored) {
                    // Membership probe must never fail the inbox read.
                }
            }
        }
        return false;
    }

    /**
     * Manager stand-down: closes an OPEN alert with a resolution note.
     * DRIVER logins use their own stand-down endpoint, never this one.
     */
    @Transactional
    public SosAlertResponse resolve(Long alertId, String note) {
        DepotPrincipal p = DepotScope.current();
        if (p == null || (p.role() != Role.OWNER && p.role() != Role.DISPATCHER)) {
            throw new AccessDeniedException("Dispatcher login required");
        }
        SosAlert alert = sosAlerts.findById(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("SosAlert", "id", alertId));
        alert.setStatus(com.zonepilot.backend.enums.SosStatus.RESOLVED);
        alert.setResolvedAt(Instant.now());
        audit.log(p.username(), "SOS_RESOLVE", null, null, note, null, null);
        return SosAlertResponse.from(sosAlerts.save(alert));
    }
}
