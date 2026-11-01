package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.DepotScope;
import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.dto.request.CreateTourRequest;
import com.zonepilot.backend.dto.response.RouteValidationResponse;
import com.zonepilot.backend.dto.response.TourResponse;
import com.zonepilot.backend.dto.response.TourTrackResponse;
import com.zonepilot.backend.entity.*;
import com.zonepilot.backend.enums.AssignmentStatus;
import com.zonepilot.backend.enums.VehicleAvailability;
import com.zonepilot.backend.exception.EligibilityException;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
import org.springframework.security.access.AccessDeniedException;
import com.zonepilot.backend.repository.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tour lifecycle: create (optimize + validate leg 1 + assign) and advance
 * (PoD/skip → validate next leg or complete the tour).
 */
@Service
public class TourService {

    private static final double POD_PROXIMITY_M = 100.0;

    /** FAILED reason chips: fixed codes plus OTHER:<free text>. */
    private static final java.util.Set<String> FAIL_CHIPS = java.util.Set.of(
            "DAMAGED", "CUSTOMER_REFUSED", "WRONG_ADDRESS", "ADDRESS_CLOSED");

    private final TourRepository tours;
    private final TourStopRepository stops;
    private final FleetGroupRepository groups;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final DispatchRouteRepository routes;
    private final GroupVehicleRepository groupVehicles;
    private final GroupDriverRepository groupDrivers;
    private final GroupScope scope;
    private final TourOptimizerService optimizer;
    private final RouteComplianceService compliance;
    private final DriverService driverService;
    private final PositionTrackingService positions;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    private final org.springframework.security.crypto.password.PasswordEncoder passwords;
    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);

    public TourService(TourRepository tours, TourStopRepository stops, FleetGroupRepository groups,
                       VehicleRepository vehicles, DriverRepository drivers,
                       DispatchRouteRepository routes, GroupScope scope,
                       TourOptimizerService optimizer, RouteComplianceService compliance,
                       DriverService driverService, PositionTrackingService positions,
                       GroupVehicleRepository groupVehicles, GroupDriverRepository groupDrivers) {
        this.tours = tours;
        this.stops = stops;
        this.groups = groups;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.routes = routes;
        this.scope = scope;
        this.optimizer = optimizer;
        this.compliance = compliance;
        this.driverService = driverService;
        this.positions = positions;
        this.groupVehicles = groupVehicles;
        this.groupDrivers = groupDrivers;
        this.audit = AuditService.noop();
        this.idempotency = new IdempotencyService();
        this.passwords = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TourService(TourRepository tours, TourStopRepository stops, FleetGroupRepository groups,
                       VehicleRepository vehicles, DriverRepository drivers,
                       DispatchRouteRepository routes, GroupScope scope,
                       TourOptimizerService optimizer, RouteComplianceService compliance,
                       DriverService driverService, PositionTrackingService positions,
                       GroupVehicleRepository groupVehicles, GroupDriverRepository groupDrivers,
                       AuditService audit, IdempotencyService idempotency,
                       org.springframework.security.crypto.password.PasswordEncoder passwords) {
        this.tours = tours;
        this.stops = stops;
        this.groups = groups;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.routes = routes;
        this.scope = scope;
        this.optimizer = optimizer;
        this.compliance = compliance;
        this.driverService = driverService;
        this.positions = positions;
        this.groupVehicles = groupVehicles;
        this.groupDrivers = groupDrivers;
        this.audit = audit == null ? AuditService.noop() : audit;
        this.idempotency = idempotency == null ? new IdempotencyService() : idempotency;
        this.passwords = passwords;
    }

    @Transactional
    public TourResponse create(CreateTourRequest req) {
        FleetGroup group = groups.findById(req.getGroupId())
                .orElseThrow(() -> new ResourceNotFoundException("FleetGroup", "id", req.getGroupId()));
        scope.requireGroupAccess(group);
        Vehicle vehicle = vehicles.findById(req.getVehicleId())
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", "id", req.getVehicleId()));
        Driver driver = drivers.findById(req.getDriverId())
                .orElseThrow(() -> new ResourceNotFoundException("Driver", "id", req.getDriverId()));
        // Cross-group create is 403: tour members must belong to the tour group.
        if (!groupVehicles.existsByGroupIdAndVehicleId(group.getId(), vehicle.getId())) {
            throw new AccessDeniedException("Vehicle " + vehicle.getId()
                    + " is not in group " + group.getId());
        }
        if (!groupDrivers.existsByGroupIdAndDriverId(group.getId(), driver.getId())) {
            throw new AccessDeniedException("Driver " + driver.getId()
                    + " is not in group " + group.getId());
        }
        // Double-book guard: a new tour starts from a free vehicle only.
        // Leg continuation reuses the same vehicle while RESERVED, so this
        // gate lives at create/dispatch time — never inside assign().
        if (vehicle.getAvailability() == VehicleAvailability.IN_TRANSIT
                || vehicle.getAvailability() == VehicleAvailability.RESERVED) {
            throw new EligibilityException("Vehicle " + vehicle.getRegistrationNumber()
                    + " is " + vehicle.getAvailability().name().toLowerCase()
                    + ", not free for a new tour");
        }
        if (req.getStops().size() > TourOptimizerService.MAX_STOPS) {
            throw new ValidationException("at most 12 stops per tour");
        }
        // Hard block: planned manifest must fit the model's rated payload,
        // before spending optimizer/Google calls.
        double plannedKg = req.getStops().stream()
                .mapToDouble(s -> s.getWeightKg() != null ? s.getWeightKg() : 0.0).sum();
        Double payloadKg = vehicle.getModel() != null ? vehicle.getModel().getPayloadKg() : null;
        LoadService.checkCapacity(payloadKg, plannedKg, vehicle.getRegistrationNumber());
        // Fail fast before optimizer/Google spend: same gate group-dispatch uses.
        driverService.checkEligible(driver, vehicle);

        Instant now = Instant.now();
        List<TourOptimizerService.StopPoint> points = req.getStops().stream()
                .map(s -> new TourOptimizerService.StopPoint(s.getLat(), s.getLng()))
                .toList();
        TourOptimizerService.Plan plan =
                optimizer.plan(req.getOriginLat(), req.getOriginLng(), points, now);
        // Pair ordered points back to inputs (exact double match — same objects Lane).
        List<CreateTourRequest.StopInput> orderedInputs = new ArrayList<>(plan.ordered().size());
        List<CreateTourRequest.StopInput> remaining = new ArrayList<>(req.getStops());
        for (TourOptimizerService.StopPoint p : plan.ordered()) {
            CreateTourRequest.StopInput match = remaining.stream()
                    .filter(s -> Double.compare(s.getLat(), p.lat()) == 0
                            && Double.compare(s.getLng(), p.lng()) == 0)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("optimizer returned unknown stop"));
            orderedInputs.add(match);
            remaining.remove(match);
        }

        Tour tour = new Tour();
        tour.setGroup(group);
        tour.setVehicle(vehicle);
        tour.setDriver(driver);
        tour.setStatus(TourStatus.ACTIVE);
        tour.setCreatedByUserId(scope.currentUserId().orElse(null));
        tours.save(tour);

        for (int i = 0; i < orderedInputs.size(); i++) {
            TourStop s = toStop(tour, i, orderedInputs.get(i));
            s.setLegDurationSec(plan.legTimesSec().get(i));
            stops.save(s);
        }
        // Anchor ETAs: eta(stop i) = departure + cumulative planned leg times.
        reanchorFrom(tour.getId(), now, 0);

        // Leg 1: origin -> stop 0.
        CreateTourRequest.StopInput first = orderedInputs.get(0);
        RouteValidationResponse leg = compliance.validateRoute(vehicle.getId(),
                req.getOriginLat(), req.getOriginLng(), first.getLat(), first.getLng());
        DispatchRoute route = routes.findById(leg.getDispatchRouteId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "DispatchRoute", "id", leg.getDispatchRouteId()));
        route.setTour(tour);
        driverService.assign(route.getId(), driver.getId());
        audit.log(actorName(), "DISPATCH", tour.getId(), null, null, null, null);
        return toResponse(tour.getId());
    }

    @Transactional(readOnly = true)
    public TourResponse get(Long tourId) {
        Tour tour = tours.findById(tourId)
                .orElseThrow(() -> new ResourceNotFoundException("Tour", "id", tourId));
        scope.requireGroupAccess(tour.getGroup());
        return toResponse(tourId);
    }

    @Transactional(readOnly = true)
    public TourTrackResponse track(Long tourId) {
        Tour tour = tours.findById(tourId)
                .orElseThrow(() -> new ResourceNotFoundException("Tour", "id", tourId));
        scope.requireGroupAccess(tour.getGroup());
        List<TourStop> ordered = stops.findByTourIdOrderBySeqAsc(tourId);
        // Routes are created one per leg in order; reverse id-desc back to seq order.
        List<DispatchRoute> legs = new ArrayList<>(routes.findByTourIdOrderByIdDesc(tourId));
        java.util.Collections.reverse(legs);
        List<TourTrackResponse.LegDto> legDtos = new ArrayList<>(legs.size());
        for (int i = 0; i < legs.size(); i++) {
            TourTrackResponse.LegDto dto = new TourTrackResponse.LegDto();
            dto.setRouteId(legs.get(i).getId());
            dto.setFromSeq(i == 0 ? -1 : ordered.get(i - 1).getSeq());
            dto.setToSeq(ordered.size() > i ? ordered.get(i).getSeq() : -1);
            if (legs.get(i).getPlannedRouteGeometry() != null) {
                dto.setGeometryWkt(legs.get(i).getPlannedRouteGeometry().toText());
            }
            legDtos.add(dto);
        }
        return TourTrackResponse.of(tourId, tour.getStatus().name(), ordered, legDtos,
                positions.getLatestPosition(tour.getVehicle().getId()));
    }

    @Transactional
    public TourResponse deliver(Long tourId, Long stopId, Double lat, Double lng) {
        com.zonepilot.backend.dto.request.DeliverStopRequest req =
                new com.zonepilot.backend.dto.request.DeliverStopRequest();
        req.setLat(lat);
        req.setLng(lng);
        return deliver(tourId, stopId, req);
    }

    /**
     * Manager PoD path (POST /api/tours/...). DRIVER logins are rejected
     * here — drivers deliver through their own endpoints, which additionally
     * require the owned tour.
     */
    @Transactional
    public TourResponse deliver(Long tourId, Long stopId,
                                com.zonepilot.backend.dto.request.DeliverStopRequest req) {
        requireNonDriver();
        return doDeliver(tourId, stopId, req, actorName(), true);
    }

    /**
     * Driver PoD path (POST /api/driver/...): owned-tour check first, then
     * the same gated core as the manager path.
     */
    @Transactional
    public TourResponse deliverForDriver(Long tourId, Long stopId,
                                         com.zonepilot.backend.dto.request.DeliverStopRequest req) {
        driverService.requireOwnedTour(tourId);
        return doDeliver(tourId, stopId, req, actorName(), false);
    }

    private TourResponse doDeliver(Long tourId, Long stopId,
                                   com.zonepilot.backend.dto.request.DeliverStopRequest req,
                                   String actor, boolean managerPath) {
        idempotency.claim(req.getOpId());
        Double lat = req.getLat();
        Double lng = req.getLng();
        if (lat == null || lng == null) {
            throw new ValidationException("GPS fix required for proof of delivery");
        }
        Tour tour = tours.findByIdWithLock(tourId)
                .orElseThrow(() -> new ResourceNotFoundException("Tour", "id", tourId));
        if (managerPath) {
            scope.requireGroupAccess(tour.getGroup());
        }
        requireActive(tour);
        TourStop stop = loadStop(tourId, stopId);
        requireCurrent(tourId, stop);
        // PoD must be at the stop: 100m proximity, re-validated server-side
        // on every call (including offline-queue replays).
        double distM = TourOptimizerService.haversineMeters(
                lat, lng, stop.getPoint().getY(), stop.getPoint().getX());
        if (distM > POD_PROXIMITY_M) {
            throw new ValidationException("proof of delivery is " + Math.round(distM)
                    + "m from stop " + stop.getSeq() + " (limit " + POD_PROXIMITY_M + "m)");
        }
        enforcePodPolicy(stop, req);
        StopStatus outcome = parseOutcome(req);
        stop.setStatus(outcome);
        stop.setDeliveredAt(Instant.now());
        stop.setPodLat(lat);
        stop.setPodLng(lng);
        if (req.getQtyDelivered() != null) {
            if (stop.getQuantity() != null && req.getQtyDelivered() > stop.getQuantity()) {
                throw new ValidationException("qtyDelivered " + req.getQtyDelivered()
                        + " exceeds planned quantity " + stop.getQuantity());
            }
            stop.setQtyDelivered(req.getQtyDelivered());
        }
        if (req.getPhotoRef() != null) {
            stop.setPodPhotoRef(req.getPhotoRef());
        }
        if (req.getSigRef() != null) {
            stop.setPodSigRef(req.getSigRef());
        }
        if (outcome == StopStatus.FAILED) {
            stop.setSkipReason(req.getReason());
        }
        advance(tour, stop);
        audit.log(actor, "POD_" + outcome.name(), tourId, stopId,
                req.getReason(), null, null);
        return toResponse(tourId);
    }

    /** Per-stop PoD gate: PIN (server hash), PHOTO, or SIG evidence required. */
    private void enforcePodPolicy(TourStop stop,
                                 com.zonepilot.backend.dto.request.DeliverStopRequest req) {
        PodPolicy policy = stop.getPodPolicy() == null ? PodPolicy.NONE : stop.getPodPolicy();
        switch (policy) {
            case NONE -> { /* tap + GPS only */ }
            case PIN -> {
                if (req.getPin() == null || stop.getPinHash() == null
                        || !passwords.matches(req.getPin(), stop.getPinHash())) {
                    throw new ValidationException("recipient PIN required for stop "
                            + stop.getSeq());
                }
            }
            case PHOTO -> {
                if (req.getPhotoRef() == null || req.getPhotoRef().isBlank()) {
                    throw new ValidationException("photo evidence required for stop "
                            + stop.getSeq());
                }
            }
            case SIG -> {
                if (req.getSigRef() == null || req.getSigRef().isBlank()) {
                    throw new ValidationException("signature required for stop "
                            + stop.getSeq());
                }
            }
        }
    }

    private static StopStatus parseOutcome(
            com.zonepilot.backend.dto.request.DeliverStopRequest req) {
        if (req.getStatus() == null || req.getStatus().isBlank()) {
            return StopStatus.DELIVERED;
        }
        StopStatus outcome;
        try {
            outcome = StopStatus.valueOf(req.getStatus());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("unknown PoD status '" + req.getStatus() + "'");
        }
        if (outcome != StopStatus.DELIVERED && outcome != StopStatus.PARTIAL
                && outcome != StopStatus.FAILED) {
            throw new ValidationException("unknown PoD status '" + req.getStatus() + "'");
        }
        if (outcome == StopStatus.FAILED) {
            checkFailChip(req.getReason());
        }
        return outcome;
    }

    /** FAILED closes with a reason chip (fixed code or OTHER:<text>). */
    static void checkFailChip(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("FAILED requires a reason chip");
        }
        String r = reason.strip();
        if (r.startsWith("OTHER:")) {
            if (r.length() <= "OTHER:".length()) {
                throw new ValidationException("OTHER: requires free text");
            }
            return;
        }
        if (!FAIL_CHIPS.contains(r)) {
            throw new ValidationException("unknown FAILED reason chip '" + r
                    + "', expected one of " + FAIL_CHIPS + " or OTHER:<text>");
        }
    }

    @Transactional
    public TourResponse completeBySeq(Long tourId, Integer seq, Double lat, Double lng) {
        com.zonepilot.backend.dto.request.DeliverStopRequest req =
                new com.zonepilot.backend.dto.request.DeliverStopRequest();
        req.setLat(lat);
        req.setLng(lng);
        return completeBySeq(tourId, seq, req);
    }

    @Transactional
    public TourResponse completeBySeq(Long tourId, Integer seq,
                                      com.zonepilot.backend.dto.request.DeliverStopRequest req) {
        // Thin seq alias for deliver: order is still enforced inside deliver().
        TourStop stop = stops.findByTourIdAndSeq(tourId, seq)
                .orElseThrow(() -> new ResourceNotFoundException("TourStop", "seq",
                        tourId + "/" + seq));
        return deliver(tourId, stop.getId(), req);
    }

    @Transactional
    public TourResponse skip(Long tourId, Long stopId, String reason) {
        return skip(tourId, stopId, reason, null);
    }

    /** Manager skip path (POST /api/tours/...): rejects DRIVER logins. */
    @Transactional
    public TourResponse skip(Long tourId, Long stopId, String reason, String opId) {
        requireNonDriver();
        return doSkip(tourId, stopId, reason, opId, actorName(), true);
    }

    /** Driver skip path (POST /api/driver/...): owned-tour check first. */
    @Transactional
    public TourResponse skipForDriver(Long tourId, Long stopId, String reason, String opId) {
        driverService.requireOwnedTour(tourId);
        return doSkip(tourId, stopId, reason, opId, actorName(), false);
    }

    private TourResponse doSkip(Long tourId, Long stopId, String reason, String opId,
                                String actor, boolean managerPath) {
        idempotency.claim(opId);
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("skip reason is required");
        }
        Tour tour = tours.findByIdWithLock(tourId)
                .orElseThrow(() -> new ResourceNotFoundException("Tour", "id", tourId));
        if (managerPath) {
            scope.requireGroupAccess(tour.getGroup());
        }
        requireActive(tour);
        TourStop stop = loadStop(tourId, stopId);
        requireCurrent(tourId, stop);
        stop.setStatus(StopStatus.SKIPPED);
        stop.setSkipReason(reason.strip());
        advance(tour, stop);
        audit.log(actor, "SKIP", tourId, stopId, reason.strip(), null, null);
        return toResponse(tourId);
    }

    @Transactional
    public TourResponse approve(Long tourId) {
        requireNonDriver();
        Tour tour = tours.findByIdWithLock(tourId)
                .orElseThrow(() -> new ResourceNotFoundException("Tour", "id", tourId));
        scope.requireGroupAccess(tour.getGroup());
        if (tour.getStatus() != TourStatus.PENDING_APPROVAL) {
            throw new ValidationException("tour is " + tour.getStatus()
                    + ", not PENDING_APPROVAL");
        }
        // Separation of duties: a DISPATCHER cannot approve a tour they
        // created (OWNER stays the backstop approver). Checked only when
        // both sides are known (unit tests run without an auth context).
        Long creator = tour.getCreatedByUserId();
        DepotPrincipal caller = DepotScope.current();
        Long me = scope.currentUserId().orElse(null);
        if (caller != null && caller.role() == Role.DISPATCHER
                && creator != null && me != null && creator.equals(me)) {
            throw new ValidationException(
                    "approver must differ from the tour creator (separation of duties)");
        }
        tour.setStatus(TourStatus.COMPLETED);
        tour.getVehicle().setAvailability(VehicleAvailability.AVAILABLE);
        completeLatestRoute(tour.getId());
        audit.log(actorName(), "APPROVE", tourId, null, null, null, null);
        return toResponse(tourId);
    }

    // current stop = first PENDING stop in seq order; mid-tour order is frozen.
    private void advance(Tour tour, TourStop done) {
        // The leg that brought the driver here is complete — the driver is
        // free for the next leg (avoids tripping the ON_TRIP eligibility check).
        completeLatestRoute(tour.getId());
        List<TourStop> ordered = stops.findByTourIdOrderBySeqAsc(tour.getId());
        TourStop next = ordered.stream()
                .filter(s -> s.getStatus() == StopStatus.PENDING)
                .findFirst()
                .orElse(null);
        if (next == null) {
            // All stops closed: park for manager approval. The vehicle stays
            // IN_TRANSIT until approve() closes the tour + releases it.
            // (Latest leg already completed above — no second call here.)
            tour.setStatus(TourStatus.PENDING_APPROVAL);
            return;
        }
        RouteValidationResponse leg = compliance.validateRoute(tour.getVehicle().getId(),
                done.getPoint().getY(), done.getPoint().getX(),
                next.getPoint().getY(), next.getPoint().getX());
        DispatchRoute route = routes.findById(leg.getDispatchRouteId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "DispatchRoute", "id", leg.getDispatchRouteId()));
        route.setTour(tour);
        driverService.assign(route.getId(), tour.getDriver().getId());
        // Re-anchor: next stop ETA = now + validated-leg actual; later stops
        // shift by their stored planned durations (mid-tour order is frozen).
        Instant now = Instant.now();
        long actual = leg.getTravelDurationSec() != null && leg.getTravelDurationSec() > 0
                ? leg.getTravelDurationSec()
                : optimizer.estimateLegSec(done.getPoint().getY(), done.getPoint().getX(),
                        next.getPoint().getY(), next.getPoint().getX());
        next.setLegDurationSec(actual);
        reanchorFrom(tour.getId(), now, ordered.indexOf(next));
    }

    /** Sets etaAt for PENDING stops from index i: anchor + cumulative leg durations. */
    private void reanchorFrom(Long tourId, Instant anchor, int fromIdx) {
        List<TourStop> ordered = stops.findByTourIdOrderBySeqAsc(tourId);
        Instant cursor = anchor;
        for (int i = fromIdx; i < ordered.size(); i++) {
            TourStop s = ordered.get(i);
            if (s.getStatus() != StopStatus.PENDING) {
                continue;
            }
            long legSec = s.getLegDurationSec() != null && s.getLegDurationSec() > 0
                    ? s.getLegDurationSec()
                    : 0;
            cursor = cursor.plusSeconds(legSec);
            s.setEtaAt(cursor);
        }
    }

    private void completeLatestRoute(Long tourId) {
        routes.findByTourIdOrderByIdDesc(tourId).stream().findFirst().ifPresent(r -> {
            if (r.getAssignmentStatus() != AssignmentStatus.DELIVERED) {
                r.setAssignmentStatus(AssignmentStatus.DELIVERED);
            }
        });
    }

    private void requireActive(Tour tour) {
        if (tour.getStatus() != TourStatus.ACTIVE) {
            throw new ValidationException("tour is " + tour.getStatus() + ", not ACTIVE");
        }
    }

    /**
     * Manager endpoints (/api/tours/...) reject DRIVER logins outright —
     * drivers act through /api/driver/..., which checks the owned tour.
     * Null callers (unit tests) pass: prod always authenticates.
     */
    private void requireNonDriver() {
        DepotPrincipal caller = DepotScope.current();
        if (caller != null && caller.role() == Role.DRIVER) {
            throw new AccessDeniedException("Driver login required — use /api/driver endpoints");
        }
    }

    private static String actorName() {
        DepotPrincipal caller = DepotScope.current();
        return caller == null ? "system" : caller.username();
    }

    private TourStop loadStop(Long tourId, Long stopId) {
        TourStop stop = stops.findById(stopId)
                .orElseThrow(() -> new ResourceNotFoundException("TourStop", "id", stopId));
        if (!stop.getTour().getId().equals(tourId)) {
            throw new ResourceNotFoundException("TourStop", "id", stopId);
        }
        return stop;
    }

    private void requireCurrent(Long tourId, TourStop stop) {
        if (stop.getStatus() != StopStatus.PENDING) {
            throw new ValidationException("stop is already " + stop.getStatus());
        }
        TourStop current = stops.findByTourIdOrderBySeqAsc(tourId).stream()
                .filter(s -> s.getStatus() == StopStatus.PENDING)
                .findFirst()
                .orElseThrow(() -> new ValidationException("no pending stops"));
        if (!current.getId().equals(stop.getId())) {
            throw new ValidationException("only the current stop (seq "
                    + current.getSeq() + ") can be closed");
        }
    }

    private TourStop toStop(Tour tour, int seq, CreateTourRequest.StopInput in) {
        TourStop s = new TourStop();
        s.setTour(tour);
        s.setSeq(seq);
        s.setPoint(gf.createPoint(new Coordinate(in.getLng(), in.getLat())));
        s.setAddress(in.getAddress());
        s.setQuantity(in.getQuantity());
        s.setWeightKg(in.getWeightKg());
        s.setRecipientName(in.getRecipientName());
        s.setRecipientPhone(in.getRecipientPhone());
        s.setWindowStart(in.getWindowStart() == null ? null : Instant.parse(in.getWindowStart()));
        s.setWindowEnd(in.getWindowEnd() == null ? null : Instant.parse(in.getWindowEnd()));
        s.setCodAmount(in.getCodAmount());
        s.setHandlingNote(in.getHandlingNote());
        PodPolicy policy = PodPolicy.from(in.getPodPolicy());
        s.setPodPolicy(policy);
        if (policy == PodPolicy.PIN) {
            // PIN is hashed server-side at create; the plaintext never lands
            // in the DB and the hash never leaves for the PWA.
            if (in.getPin() == null || in.getPin().length() < 4) {
                throw new ValidationException("PIN-policy stops require a 4+ digit PIN");
            }
            s.setPinHash(passwords.encode(in.getPin()));
        }
        s.setStatus(StopStatus.PENDING);
        return s;
    }

    private TourResponse toResponse(Long tourId) {
        Tour tour = tours.findById(tourId)
                .orElseThrow(() -> new ResourceNotFoundException("Tour", "id", tourId));
        TourResponse r = new TourResponse();
        r.setId(tour.getId());
        r.setStatus(tour.getStatus().name());
        r.setDutyStatus(tour.getDutyStatus().name());
        r.setGroupId(tour.getGroup().getId());
        r.setVehicleId(tour.getVehicle().getId());
        r.setDriverId(tour.getDriver().getId());
        r.setStops(stops.findByTourIdOrderBySeqAsc(tourId).stream()
                .map(TourResponse.StopDto::from).toList());
        routes.findByTourIdOrderByIdDesc(tourId).stream().findFirst()
                .ifPresent(latest -> r.setActiveRouteId(latest.getId()));
        return r;
    }
}
