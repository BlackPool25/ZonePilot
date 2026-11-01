package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.ZoneBreachLog;
import com.zonepilot.backend.exception.RoutingException;
import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.repository.ZoneBreachLogRepository;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BreachService {

    private static final Logger log = LoggerFactory.getLogger(BreachService.class);

    private final ZoneBreachLogRepository breachLogRepository;
    private final DispatchRouteRepository dispatchRouteRepository;
    private final RoutingService routingService;
    private final JdbcTemplate jdbcTemplate;

    public BreachService(ZoneBreachLogRepository breachLogRepository,
                         DispatchRouteRepository dispatchRouteRepository,
                         RoutingService routingService,
                         JdbcTemplate jdbcTemplate) {
        this.breachLogRepository = breachLogRepository;
        this.dispatchRouteRepository = dispatchRouteRepository;
        this.routingService = routingService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Computes a reroute from the vehicle's current position that avoids the breached zone.
     * The destination is taken from the vehicle's most recent dispatch route if available.
     * The resolved geometry is persisted on the breach log record.
     *
     * Joins the caller's txn (BreachWorker's own background txn — never the
     * ingest txn: a failed road query aborts the whole Postgres txn and the
     * ingest would 500, Task 3 Major). The road-network reads themselves run
     * isolated (RoutingService REQUIRES_NEW) so a missing road network can
     * never roll back the breach insert — the row stays, the reroute is skipped.
     */
    @Transactional
    public void computeReroute(ZoneBreachLog breach, Long vehicleId,
                                double currentLat, double currentLng) {
        try {
            double[] dest = findDestinationLatLng(vehicleId);
            if (dest == null) {
                log.info("No active dispatch route found for vehicle {}; skipping reroute for breach {}",
                        vehicleId, breach.getId());
                return;
            }

            Long zoneId = breach.getZone().getId();
            LineString rerouteGeometry = routingService.computeZoneAvoidingReroute(
                    currentLat, currentLng, dest[0], dest[1], zoneId);
            if (rerouteGeometry == null) {
                log.warn("No zone-avoiding reroute available for breach {}", breach.getId());
                return;
            }

            breach.setResolvedRouteGeometry(rerouteGeometry);
            breachLogRepository.save(breach);
            log.info("Reroute computed for breach {} in zone {}: {} points",
                    breach.getId(), breach.getZone().getName(), rerouteGeometry.getNumPoints());

        } catch (RoutingException e) {
            log.warn("Reroute routing failed for breach {}: {}", breach.getId(), e.getMessage());
        } catch (Exception e) {
            log.warn("Unexpected error computing reroute for breach {}: {}", breach.getId(), e.getMessage());
        }
    }

    /**
     * Epic 3: Computes a reroute for a vehicle confirmed off-route (no zone breach).
     * Uses the vehicle's active dispatch route destination.
     *
     * Called by BreachWorker inside the worker's own background txn — see computeReroute.
     */
    @Transactional
    public void computeOffRouteReroute(Long vehicleId, double currentLat, double currentLng) {
        try {
            double[] dest = findDestinationLatLng(vehicleId);
            if (dest == null) {
                log.info("Off-route reroute: no active dispatch route for vehicle {}", vehicleId);
                return;
            }

            // Straight reroute — no zone to avoid
            LineString rerouteGeometry = routingService.computeDirectReroute(
                    currentLat, currentLng, dest[0], dest[1]);
            if (rerouteGeometry == null) {
                log.warn("Off-route reroute: no route available for vehicle {}", vehicleId);
                return;
            }
            log.info("Off-route reroute computed for vehicle {}: {} points",
                    vehicleId, rerouteGeometry.getNumPoints());

            // Update the vehicle's active dispatch route geometry
            List<Object[]> rows = jdbcTemplate.query(
                    "SELECT id FROM dispatch_route WHERE vehicle_id = ? ORDER BY created_at DESC LIMIT 1",
                    (rs, rowNum) -> new Object[]{rs.getLong("id")},
                    vehicleId);
            if (!rows.isEmpty()) {
                Long routeId = (Long) rows.get(0)[0];
                dispatchRouteRepository.findById(routeId).ifPresent(route -> {
                    route.setPlannedRouteGeometry(rerouteGeometry);
                    dispatchRouteRepository.save(route);
                });
            }

        } catch (Exception e) {
            log.warn("Off-route reroute failed for vehicle {}: {}", vehicleId, e.getMessage());
        }
    }

    private double[] findDestinationLatLng(Long vehicleId) {
        try {
            List<Object[]> rows = jdbcTemplate.query(
                    "SELECT ST_Y(destination_point) AS lat, ST_X(destination_point) AS lng " +
                    "FROM dispatch_route WHERE vehicle_id = ? " +
                    "ORDER BY created_at DESC LIMIT 1",
                    (rs, rowNum) -> new Object[]{rs.getDouble("lat"), rs.getDouble("lng")},
                    vehicleId);

            if (rows.isEmpty()) return null;

            return new double[]{(double) rows.get(0)[0], (double) rows.get(0)[1]};
        } catch (Exception e) {
            log.warn("Could not find destination for vehicle {}: {}", vehicleId, e.getMessage());
            return null;
        }
    }
}
