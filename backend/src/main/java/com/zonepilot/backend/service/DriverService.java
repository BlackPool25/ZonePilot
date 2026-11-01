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
import com.zonepilot.backend.dto.response.DutyLogResponse;
import com.zonepilot.backend.dto.response.TourResponse;
import com.zonepilot.backend.entity.*;
import com.zonepilot.backend.enums.*;
import com.zonepilot.backend.exception.EligibilityException;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.*;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Slice 2: driver roster, assignment gate (Accept/Start), slips, SOS, doc wall.
 * Single-fleet model; group ownership scoping lives in GroupScope.
 */
@Service
public class DriverService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Min gap between duty transitions: PWA offline-queue spam throttle. */
    static final long DUTY_MIN_GAP_SEC = 5;
    private static final List<AssignmentStatus> ON_TRIP =
            List.of(AssignmentStatus.ASSIGNED, AssignmentStatus.ACCEPTED, AssignmentStatus.IN_TRANSIT);

    private final DriverRepository drivers;
    private final DispatchRouteRepository routes;
    private final VehicleDocumentRepository documents;
    private final TripSlipRepository slips;
    private final SosAlertRepository sosAlerts;
    private final AppUserRepository users;
    private final VehicleRepository vehicleRepository;
    private final TourRepository tourRepository;
    private final TourStopRepository tourStopRepository;
    private final DutyLogRepository dutyLogs;
    private final AuthService authService;
    private final GeometryFactory geometryFactory =
            new GeometryFactory(new PrecisionModel(), 4326);

    public DriverService(DriverRepository drivers,
                          DispatchRouteRepository routes, VehicleDocumentRepository documents,
                          TripSlipRepository slips, SosAlertRepository sosAlerts,
                          AppUserRepository users, VehicleRepository vehicleRepository,
                          TourRepository tourRepository, TourStopRepository tourStopRepository,
                          DutyLogRepository dutyLogs,
                          AuthService authService) {
        this.drivers = drivers;
        this.routes = routes;
        this.documents = documents;
        this.slips = slips;
        this.sosAlerts = sosAlerts;
        this.users = users;
        this.vehicleRepository = vehicleRepository;
        this.tourRepository = tourRepository;
        this.tourStopRepository = tourStopRepository;
        this.dutyLogs = dutyLogs;
        this.authService = authService;
    }

    @Transactional
    public DriverResponse createDriver(String name, String phone, String licenceNo, LocalDate licenceExpiry,
                               SalaryMode salaryMode, String email, String password) {
        if (drivers.existsByLicenceNo(licenceNo)) {
            throw new ValidationException("Driver with licence number " + licenceNo + " already exists");
        }
        authService.register(email, password, Role.DRIVER);
        Driver driver = new Driver();
        driver.setName(name);
        driver.setPhone(phone);
        driver.setLicenceNo(licenceNo);
        driver.setLicenceExpiry(licenceExpiry);
        driver.setSalaryMode(salaryMode);
        driver.setUser(users.findByEmail(email.toLowerCase()).orElseThrow());
        return DriverResponse.from(drivers.save(driver));
    }

    @Transactional(readOnly = true)
    public List<DriverResponse> listDrivers() {
        return drivers.findAll().stream().map(DriverResponse::from).toList();
    }

    /**
     * Assign a driver to a route. No availability gate here by design: leg
     * continuation re-assigns the same (RESERVED) vehicle to the next leg, so
     * the IN_TRANSIT/RESERVED double-book gate lives at tour-create and
     * group-dispatch time (TourService.create, GroupDispatchService).
     */
    @Transactional
    public DispatchRoute assign(Long routeId, Long driverId) {
        DispatchRoute route = routes.findById(routeId)
                .orElseThrow(() -> new ResourceNotFoundException("DispatchRoute", "id", routeId));
        Driver driver = drivers.findById(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver", "id", driverId));
        if (route.getAssignmentStatus() != AssignmentStatus.UNASSIGNED) {
            throw new EligibilityException("Route is already " + route.getAssignmentStatus().name().toLowerCase()
                    + "; unassign before reassigning");
        }
        checkEligible(driver, route.getVehicle());
        route.setDriver(driver);
        route.setAssignmentStatus(AssignmentStatus.ASSIGNED);
        route.setAssignedAt(Instant.now());
        route.getVehicle().setAvailability(VehicleAvailability.RESERVED);
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
        route.getVehicle().setAvailability(VehicleAvailability.IN_TRANSIT);
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
        try {
            slip.setKind(SlipKind.valueOf(request.getKind()));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ValidationException("unknown slip kind '" + request.getKind()
                    + "', expected FUEL or TOLL");
        }
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
        // Offline replay: last-known fix + accuracy + stale flag. Unknown
        // location stays NULL (never 0,0); the dispatcher list flags it.
        alert.setAccuracyM(request.getAccuracyM());
        alert.setStale(Boolean.TRUE.equals(request.getStale()));
        // No GPS (offline queue, denied permission) => unknown location, never (0,0).
        if (request.getLat() != null && request.getLng() != null) {
            if (!GeoBounds.inBounds(request.getLat(), request.getLng())) {
                throw new ValidationException("SOS coordinates ("
                        + request.getLat() + ", " + request.getLng()
                        + ") are outside Bangalore operating bounds");
            }
            alert.setLocation(geometryFactory.createPoint(
                    new Coordinate(request.getLng(), request.getLat())));
        }
        return sosAlerts.save(alert);
    }

    @Transactional
    public DocumentResponse saveDocument(Long vehicleId, DocType type, String docNo, LocalDate expiryDate) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", "id", vehicleId));
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
        return documents.findExpiringBetween(today, today.plusDays(days)).stream()
                .map(DocumentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TourResponse> myTours() {
        Driver driver = currentDriver();
        List<Tour> mine = tourRepository.findByDriverIdOrderByIdDesc(driver.getId());
        if (mine.isEmpty()) {
            return List.of();
        }
        // Batched: 1 tour query above + 1 stops + 1 routes (was 3N via per-tour toTourResponse).
        // Group/vehicle/driver are EAGER/LAZY-safe in-memory joins — no extra queries here.
        List<Long> ids = mine.stream().map(Tour::getId).toList();
        Map<Long, List<TourStop>> stopsByTour = tourStopRepository
                .findByTourIdInOrderByTourIdAscSeqAsc(ids).stream()
                .collect(Collectors.groupingBy(s -> s.getTour().getId()));
        Map<Long, DispatchRoute> latestRoute = new HashMap<>();
        for (DispatchRoute r : routes.findByTourIdIn(ids)) {
            latestRoute.merge(r.getTour().getId(), r,
                    (a, b) -> a.getId() >= b.getId() ? a : b);
        }
        return mine.stream()
                .map(t -> toTourResponse(t,
                        stopsByTour.getOrDefault(t.getId(), List.of()),
                        latestRoute.get(t.getId())))
                .toList();
    }

    /**
     * Active-only myTours window, paged (COMPLETED tours excluded — the driver
     * app lists open work, history stays server-side).
     */
    @Transactional(readOnly = true)
    public Page<TourResponse> myActiveTours(Pageable pageable) {
        Driver driver = currentDriver();
        Page<Tour> page = tourRepository.findByDriverIdAndStatusInOrderByIdDesc(
                driver.getId(), List.of(TourStatus.ACTIVE, TourStatus.PENDING_APPROVAL), pageable);
        if (page.isEmpty()) {
            return Page.empty(pageable);
        }
        List<Long> ids = page.stream().map(Tour::getId).toList();
        Map<Long, List<TourStop>> stopsByTour = tourStopRepository
                .findByTourIdInOrderByTourIdAscSeqAsc(ids).stream()
                .collect(Collectors.groupingBy(s -> s.getTour().getId()));
        Map<Long, DispatchRoute> latestRoute = new HashMap<>();
        for (DispatchRoute r : routes.findByTourIdIn(ids)) {
            latestRoute.merge(r.getTour().getId(), r,
                    (a, b) -> a.getId() >= b.getId() ? a : b);
        }
        List<TourResponse> content = page.stream()
                // Defensive: the query already restricts status, but never leak
                // a terminal tour into the open-work window on a missed filter.
                .filter(t -> t.getStatus() == TourStatus.ACTIVE
                        || t.getStatus() == TourStatus.PENDING_APPROVAL)
                .map(t -> toTourResponse(t,
                        stopsByTour.getOrDefault(t.getId(), List.of()),
                        latestRoute.get(t.getId())))
                .toList();
        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    @Transactional
    public TourResponse setTourDuty(Long tourId, DutyStatus duty) {
        Driver driver = currentDriver();
        Tour tour = tourRepository.findById(tourId)
                .orElseThrow(() -> new ResourceNotFoundException("Tour", "id", tourId));
        if (tour.getDriver() == null || !tour.getDriver().getId().equals(driver.getId())) {
            throw new AccessDeniedException("Tour is not your assignment");
        }
        // Spam throttle: PWA offline-queue replays must not flood duty_log.
        List<DutyLog> prior = dutyLogs.findByTourIdOrderByRecordedAtAscIdAsc(tourId);
        if (!prior.isEmpty()) {
            Instant last = prior.get(prior.size() - 1).getRecordedAt();
            if (last != null && last.isAfter(Instant.now().minusSeconds(DUTY_MIN_GAP_SEC))) {
                throw new ValidationException("duty update too frequent (min gap "
                        + DUTY_MIN_GAP_SEC + "s)");
            }
        }
        tour.setDutyStatus(duty);
        // ELD hybrid: DRIVING = motion (rolling), IN_TRANSIT = dispatched leg
        // state (may be stationary). Both hold the vehicle IN_TRANSIT.
        // OFF_DUTY is mid-tour rest: the vehicle stays held (downgrade to
        // RESERVED, never AVAILABLE) — only tour close frees it.
        if (duty == DutyStatus.DRIVING || duty == DutyStatus.IN_TRANSIT) {
            tour.getVehicle().setAvailability(VehicleAvailability.IN_TRANSIT);
        } else if (duty == DutyStatus.OFF_DUTY) {
            if (tour.getVehicle().getAvailability() == VehicleAvailability.IN_TRANSIT) {
                tour.getVehicle().setAvailability(VehicleAvailability.RESERVED);
            }
        } else {
            tour.getVehicle().setAvailability(VehicleAvailability.RESERVED);
        }
        DutyLog log = new DutyLog();
        log.setTour(tour);
        log.setDriver(driver);
        log.setDutyStatus(duty);
        log.setRecordedAt(Instant.now());
        dutyLogs.save(log);
        return toTourResponse(tourId);
    }

    @Transactional(readOnly = true)
    public List<DutyLogResponse> dutyLog(Long tourId) {
        requireOwnedTour(tourId);
        return dutyLogs.findByTourIdOrderByRecordedAtAscIdAsc(tourId).stream()
                .map(DutyLogResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public Tour requireOwnedTour(Long tourId) {
        Driver driver = currentDriver();
        Tour tour = tourRepository.findById(tourId)
                .orElseThrow(() -> new ResourceNotFoundException("Tour", "id", tourId));
        if (tour.getDriver() == null || !tour.getDriver().getId().equals(driver.getId())) {
            throw new AccessDeniedException("Tour is not your assignment");
        }
        return tour;
    }

    private TourResponse toTourResponse(Long tourId) {
        Tour tour = tourRepository.findById(tourId)
                .orElseThrow(() -> new ResourceNotFoundException("Tour", "id", tourId));
        return toTourResponse(tour,
                tourStopRepository.findByTourIdOrderBySeqAsc(tourId),
                routes.findByTourIdOrderByIdDesc(tourId).stream().findFirst().orElse(null));
    }

    private static TourResponse toTourResponse(Tour tour, List<TourStop> orderedStops,
                                               DispatchRoute latestRoute) {
        TourResponse r = new TourResponse();
        r.setId(tour.getId());
        r.setStatus(tour.getStatus().name());
        r.setDutyStatus(tour.getDutyStatus().name());
        r.setGroupId(tour.getGroup().getId());
        r.setVehicleId(tour.getVehicle().getId());
        r.setDriverId(tour.getDriver().getId());
        r.setStops(orderedStops.stream().map(TourResponse.StopDto::from).toList());
        if (latestRoute != null) {
            r.setActiveRouteId(latestRoute.getId());
        }
        return r;
    }

    public void checkEligible(Driver driver, Vehicle vehicle) {
        LocalDate today = LocalDate.now(IST);
        if (!Boolean.TRUE.equals(driver.getIsActive())) {
            throw new EligibilityException("Driver " + driver.getName() + " is marked OFF duty");
        }
        if (vehicle.getAvailability() == VehicleAvailability.MAINTENANCE
                || vehicle.getAvailability() == VehicleAvailability.OFFLINE) {
            throw new EligibilityException("Vehicle " + vehicle.getRegistrationNumber()
                    + " is " + vehicle.getAvailability().name() + ", not dispatchable");
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
}
