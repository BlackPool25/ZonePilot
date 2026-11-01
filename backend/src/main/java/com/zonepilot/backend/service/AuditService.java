package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.AuditLog;
import com.zonepilot.backend.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Best-effort audit trail: writes never fail the business transaction
 * (a logging failure is logged, not thrown). A null repository (unit-test
 * {@link #noop()}) drops rows silently.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogs;

    public AuditService(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    /** Unit-test / legacy-path instance that drops rows. */
    public static AuditService noop() {
        return new AuditService(null);
    }

    public void log(String actor, String action, Long tourId, Long stopId,
                    String reason, String ip, String device) {
        if (auditLogs == null) {
            return;
        }
        try {
            AuditLog row = new AuditLog();
            row.setActor(actor);
            row.setAction(action);
            row.setTourId(tourId);
            row.setStopId(stopId);
            row.setReason(reason);
            row.setIp(ip);
            row.setDevice(device);
            auditLogs.save(row);
        } catch (RuntimeException e) {
            // Audit must never break dispatch/PoD/SOS.
            log.warn("audit.write_failed action={} err={}", action, e.toString());
        }
    }
}
