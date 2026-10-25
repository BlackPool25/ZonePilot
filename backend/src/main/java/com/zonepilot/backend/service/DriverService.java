package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.auth.AuthService;
import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.DepotScope;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.dto.request.CreateSlipRequest;
import com.zonepilot.backend.dto.request.SosRequest;
import com.zonepilot.backend.dto.response.DocumentResponse;
import com.zonepilot.backend.dto.response.DriverResponse;
import com.zonepilot.backend.entity.*;
import com.zonepilot.backend.enums.*;
import com.zonepilot.backend.exception.EligibilityException;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.*;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Slice 2: driver roster, assignment gate (Accept/Start), slips, SOS, doc wall.
 * Client-sent depot ids are untrusted: everything is confined to the JWT depot.
 */
@Service
public class DriverService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final List<AssignmentStatus> ON_TRIP =
            List.of(AssignmentStatus.ASSIGNED, AssignmentStatus.ACCEPTED, AssignmentStatus.IN_TRANSIT);

    private final DriverRepository drivers;
    private final DepotRepository depots;
    private final DispatchRouteRepository routes;
    private final VehicleDocumentRepository documents;
    private final TripSlipRepository slips;
    private final SosAlertRepository sosAlerts;
    private final AppUserRepository users;
    private final VehicleRepository vehicleRepository;
    private final AuthService authService;
    private final DepotScope depotScope;
    private final GeometryFactory geometryFactory =
            new GeometryFactory(new PrecisionModel(), 4326);

    public DriverService(DriverRepository drivers, DepotRepository depots,
                         DispatchRouteRepository routes, VehicleDocumentRepository documents,
                         TripSlipRepository slips, SosAlertRepository sosAlerts,
                         AppUserRepository users, VehicleRepository vehicleRepository,
                         AuthService authService, DepotScope depotScope) {
        this.drivers = drivers;
        this.depots = depots;
        this.routes = routes;
        this.documents = documents;
        this.slips = slips;
        this.sosAlerts = sosAlerts;
        this.users = users;
        this.vehicleRepository = vehicleRepository;
        this.authService = authService;
        this.depotScope = depotScope;
    }

    @Transactional
    public DriverResponse createDriver(String name, String phone, String licenceNo, LocalDate licenceExpiry,
                               SalaryMode salaryMode, Long depotId, String email, String password) {
        requireDepotAccess(depotId);
        if (drivers.existsByLicenceNo(licenceNo)) {
            throw new ValidationException("Driver with licence number " + licenceNo + " already exists");
        }
        Depot depot = depots.findById(depotId)
                .orElseThrow(() -> new ResourceNotFoundException("Depot", "id", depotId));
        authService.register(email, password, Role.DRIVER, depotId);
        Driver driver = new Driver();
        driver.setName(name);
        driver.setPhone(phone);
        driver.setLicenceNo(licenceNo);
        driver.setLicenceExpiry(licenceExpiry);
        driver.setSalaryMode(salaryMode);
        driver.setDepot(depot);
        driver.setUser(users.findByEmail(email.toLowerCase()).orElseThrow());
        return DriverResponse.from(drivers.save(driver));
    }

    @Transactional(readOnly = true)
    public List<DriverResponse> listDrivers() {
        depotScope.apply();
        return drivers.findAll().stream().map(DriverResponse::from).toList();
    }

    @Transactional
    public DispatchRoute assign(Long routeId, Long driverId) {
        DispatchRoute route = routes.findById(routeId)
                .orElseThrow(() -> new ResourceNotFoundException("DispatchRoute", "id", routeId));
        Driver driver = drivers.findById(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver", "id", driverId));
        requireDepotAccess(driver);
        requireDepotAccess(route.getVehicle());
        if (route.getAssignmentStatus() != AssignmentStatus.UNASSIGNED) {
            throw new EligibilityException("Route is already " + route.getAssignmentStatus().name().toLowerCase()
                    + "; unassign before reassigning");
        }
        checkEligible(driver, route.getVehicle());
        route.setDriver(driver);
        route.setAssignmentStatus(AssignmentStatus.ASSIGNED);
        route.setAssignedAt(Instant.now());
        return routes.save(route);
    }

    @Transactional(readOnly = true)
    public List<DispatchRoute> myRoutes() {
        Driver driver = currentDriver();
        return routes.findByDriverIdAndAssignmentStatusInOrderByAssignedAtDesc(driver.getId(), ON_TRIP);
    }

    @Transactional
    public DispatchRoute accept(Long routeId) {
        DispatchRoute route = ownedRoute(routeId);
        if (route.getAssignmentStatus() != AssignmentStatus.ASSIGNED) {
            throw new EligibilityException("Route cannot be accepted from state "
                    + route.getAssignmentStatus().name().toLowerCase());
        }
        route.setAssignmentStatus(AssignmentStatus.ACCEPTED);
        return routes.save(route);
    }

    @Transactional
    public DispatchRoute start(Long routeId) {
        DispatchRoute route = ownedRoute(routeId);
        if (route.getAssignmentStatus() != AssignmentStatus.ACCEPTED) {
            throw new EligibilityException("Route must be accepted before starting");
        }
        route.setAssignmentStatus(AssignmentStatus.IN_TRANSIT);
        route.setStartedAt(Instant.now());
        return routes.save(route);
    }

    @Transactional
    public TripSlip slip(Long routeId, CreateSlipRequest request) {
        DispatchRoute route = ownedRoute(routeId);
        if (route.getAssignmentStatus() != AssignmentStatus.IN_TRANSIT) {
            throw new EligibilityException("Slips can only be recorded while in transit");
        }
        TripSlip slip = new TripSlip();
        slip.setRoute(route);
        slip.setDriver(route.getDriver());
        slip.setKind(SlipKind.valueOf(request.getKind()));
        slip.setAmount(BigDecimal.valueOf(request.getAmount()));
        return slips.save(slip);
    }

    @Transactional
    public SosAlert sos(SosRequest request) {
        Driver driver = currentDriver();
        DispatchRoute route = null;
        if (request.getRouteId() != null) {
            route = routes.findById(request.getRouteId())
                    .orElseThrow(() -> new ResourceNotFoundException("DispatchRoute", "id", request.getRouteId()));
            if (route.getDriver() == null || !route.getDriver().getId().equals(driver.getId())) {
                throw new AccessDeniedException("SOS route is not your assignment");
            }
        }
        SosAlert alert = new SosAlert();
        alert.setDriver(driver);
        alert.setRoute(route);
        // No GPS (offline queue, denied permission) => unknown location, never (0,0).
        if (request.getLat() != null && request.getLng() != null) {
            alert.setLocation(geometryFactory.createPoint(
                    new Coordinate(request.getLng(), request.getLat())));
        }
        return sosAlerts.save(alert);
    }

    @Transactional
    public DocumentResponse saveDocument(Long vehicleId, DocType type, String docNo, LocalDate expiryDate) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", "id", vehicleId));
        requireDepotAccess(vehicle);
        VehicleDocument doc = documents.findByVehicleIdAndType(vehicleId, type).orElseGet(VehicleDocument::new);
        doc.setVehicle(vehicle);
        doc.setType(type);
        doc.setDocNo(docNo);
        doc.setExpiryDate(expiryDate);
        return DocumentResponse.from(documents.save(doc));
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> expiringDocs(int days) {
        LocalDate today = LocalDate.now(IST);
        DepotPrincipal p = DepotScope.current();
        return documents.findExpiringBetween(today, today.plusDays(days)).stream()
                .filter(d -> p == null || p.isOwner()
                        || (d.getVehicle().getDepot() != null
                                && d.getVehicle().getDepot().getId().equals(p.depotId())))
                .map(DocumentResponse::from)
                .toList();
    }

    public void checkEligible(Driver driver, Vehicle vehicle) {
        LocalDate today = LocalDate.now(IST);
        if (!Boolean.TRUE.equals(driver.getIsActive())) {
            throw new EligibilityException("Driver " + driver.getName() + " is marked OFF duty");
        }
        if (!driver.licenceValidOn(today)) {
            throw new EligibilityException("Driver " + driver.getName()
                    + "'s licence expired on " + driver.getLicenceExpiry());
        }
        if (routes.existsByDriverIdAndAssignmentStatusIn(driver.getId(), ON_TRIP)) {
            throw new EligibilityException("Driver " + driver.getName() + " is already on a trip");
        }
        List<String> expired = documents.findByVehicleId(vehicle.getId()).stream()
                .filter(d -> d.expiredOn(today))
                .map(d -> d.getType().name() + " expired " + d.getExpiryDate())
                .toList();
        if (!expired.isEmpty()) {
            throw new EligibilityException("Vehicle " + vehicle.getRegistrationNumber()
                    + " blocked: " + String.join(", ", expired));
        }
    }

    private DispatchRoute ownedRoute(Long routeId) {
        Driver driver = currentDriver();
        DispatchRoute route = routes.findById(routeId)
                .orElseThrow(() -> new ResourceNotFoundException("DispatchRoute", "id", routeId));
        if (route.getDriver() == null || !route.getDriver().getId().equals(driver.getId())) {
            throw new AccessDeniedException("Route is not your assignment");
        }
        return route;
    }

    private Driver currentDriver() {
        DepotPrincipal p = DepotScope.current();
        if (p == null || p.role() != Role.DRIVER) {
            throw new AccessDeniedException("Driver login required");
        }
        Long userId = users.findByEmail(p.username())
                .orElseThrow(() -> new AccessDeniedException("Unknown driver login")).getId();
        return drivers.findByUserId(userId)
                .orElseThrow(() -> new AccessDeniedException("No driver profile linked to this login"));
    }

    private void requireDepotAccess(Driver driver) {
        requireDepotAccess(driver.getDepot() == null ? null : driver.getDepot().getId());
    }

    private void requireDepotAccess(Vehicle vehicle) {
        requireDepotAccess(vehicle.getDepot() == null ? null : vehicle.getDepot().getId());
    }

    private void requireDepotAccess(Long depotId) {
        DepotPrincipal p = DepotScope.current();
        if (p == null || p.isOwner()) {
            return;
        }
        if (depotId == null || !depotId.equals(p.depotId())) {
            throw new AccessDeniedException("Cross-depot access denied");
        }
    }
}
