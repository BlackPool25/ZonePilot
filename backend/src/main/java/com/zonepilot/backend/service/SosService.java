package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.DepotScope;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.dto.response.SosAlertResponse;
import com.zonepilot.backend.repository.SosAlertRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Dispatcher SOS inbox: newest first with unknown/stale flags for the UI warning. */
@Service
public class SosService {

    private final SosAlertRepository sosAlerts;

    public SosService(SosAlertRepository sosAlerts) {
        this.sosAlerts = sosAlerts;
    }

    @Transactional(readOnly = true)
    public List<SosAlertResponse> listForDispatch() {
        DepotPrincipal p = DepotScope.current();
        if (p == null || (p.role() != Role.OWNER && p.role() != Role.DISPATCHER)) {
            throw new AccessDeniedException("Dispatcher login required");
        }
        return sosAlerts.findAllByOrderByCreatedAtDesc().stream()
                .map(SosAlertResponse::from).toList();
    }
}
