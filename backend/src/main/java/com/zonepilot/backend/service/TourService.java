package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.dto.request.CreateTourRequest;
import com.zonepilot.backend.dto.response.RouteValidationResponse;
import com.zonepilot.backend.dto.response.TourResponse;
import com.zonepilot.backend.dto.response.TourTrackResponse;
import com.zonepilot.backend.entity.*;
import com.zonepilot.backend.enums.AssignmentStatus;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
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

    private final TourRepository tours;
    private final TourStopRepository stops;
    private final FleetGroupRepository groups;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final DispatchRouteRepository routes;
    private final GroupScope scope;
    private final TourOptimizerService optimizer;
    private final RouteComplianceService compliance;
    private final DriverService driverService;
    private final PositionTrackingService positions;
    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);

    public TourService(TourRepository tours, TourStopRepository stops, FleetGroupRepository groups,
                       VehicleRepository vehicles, DriverRepository drivers,
                       DispatchRouteRepository routes, GroupScope scope,
                       TourOptimizerService optimizer, RouteComplianceService compliance,
                       DriverService driverService, PositionTrackingService positions) {
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
        if (req.getStops().size() > TourOptimizerService.MAX_STOPS) {
            throw new ValidationException("at most 12 stops per tour");
        }
        // Hard block: planned manifest must fit the model's rated payload,
        // before spending optimizer/Google calls.
        double plannedKg = req.getStops().stream()
                .mapToDouble(s -> s.getWeightKg() != null ? s.getWeightKg() : 0.0).sum();
        Double payloadKg = vehicle.getModel() != null ? vehicle.getModel().getPayloadKg() : null;
        LoadService.checkCapacity(payloadKg, plannedKg, vehicle.getRegistrationNumber());

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
        return toResponse(tour.getId());
    }

    @Transactional(readOnly = true)
    public TourResponse get(Long tourId) {
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
    public TourResponse deliver(Long tourId, Long stopId, double lat, double lng) {
        Tour tour = tours.findByIdWithLock(tourId)
                .orElseThrow(() -> new ResourceNotFoundException("Tour", "id", tourId));
        requireActive(tour);
        TourStop stop = loadStop(tourId, stopId);
        requireCurrent(tourId, stop);
        stop.setStatus(StopStatus.DELIVERED);
        stop.setDeliveredAt(Instant.now());
        stop.setPodLat(lat);
        stop.setPodLng(lng);
        advance(tour, stop);
        return toResponse(tourId);
    }

    @Transactional
    public TourResponse skip(Long tourId, Long stopId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("skip reason is required");
        }
        Tour tour = tours.findByIdWithLock(tourId)
                .orElseThrow(() -> new ResourceNotFoundException("Tour", "id", tourId));
        requireActive(tour);
        TourStop stop = loadStop(tourId, stopId);
        requireCurrent(tourId, stop);
        stop.setStatus(StopStatus.SKIPPED);
        stop.setSkipReason(reason.strip());
        advance(tour, stop);
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
            tour.setStatus(TourStatus.COMPLETED);
            completeLatestRoute(tour.getId());
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
        s.setStatus(StopStatus.PENDING);
        return s;
    }

    private TourResponse toResponse(Long tourId) {
        Tour tour = tours.findById(tourId)
                .orElseThrow(() -> new ResourceNotFoundException("Tour", "id", tourId));
        TourResponse r = new TourResponse();
        r.setId(tour.getId());
        r.setStatus(tour.getStatus().name());
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
