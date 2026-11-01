package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.AppUser;
import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.dto.request.DeliverStopRequest;
import com.zonepilot.backend.dto.request.SosRequest;
import com.zonepilot.backend.entity.*;
import com.zonepilot.backend.enums.AssignmentStatus;
import com.zonepilot.backend.enums.DutyStatus;
import com.zonepilot.backend.enums.SosStatus;
import com.zonepilot.backend.enums.VehicleAvailability;
import com.zonepilot.backend.exception.ConflictException;
import com.zonepilot.backend.exception.EligibilityException;
import com.zonepilot.backend.exception.RateLimitExceededException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RED: audit feedback — /latest + SOS scoping, SOS rate/proximity, PoD policy,
 * approve separation-of-duties, opId idempotency, duty annotate/correction,
 * FAILED/PARTIAL + chips, shift semantics, replay purge, partition monitor.
 */
@ExtendWith(MockitoExtension.class)
class DispatchDriverOpsTest {

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    @Mock TourRepository tours;
    @Mock TourStopRepository stops;
    @Mock FleetGroupRepository groups;
    @Mock VehicleRepository vehicles;
    @Mock DriverRepository drivers;
    @Mock DispatchRouteRepository routes;
    @Mock GroupScope scope;
    @Mock TourOptimizerService optimizer;
    @Mock RouteComplianceService compliance;
    @Mock DriverService driverService;
    @Mock PositionTrackingService positions;

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private void login(Role role, String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new DepotPrincipal(email, null, role), null, List.of()));
    }

    // ── /latest scoping ──────────────────────────────

    @Test
    void latest_crossGroup_denied() {
        var logs = mock(VehiclePositionLogRepository.class);
        var svc = new PositionTrackingService(vehicles, logs,
                mock(DispatchRouteRepository.class), mock(TourRepository.class),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(TickBroadcaster.class), mock(DriverRepository.class),
                mock(AppUserRepository.class), scope,
                mock(FleetGroupRepository.class), mock(GroupVehicleRepository.class));
        login(Role.DISPATCHER, "m@zp.test");
        assertThrows(AccessDeniedException.class, () -> svc.getLatestPosition(1L));
    }

    // ── SOS inbox scoping + resolve ──────────────────

    private SosService sosService(SosAlertRepository repo, AuditService audit) {
        return new SosService(repo, scope, mock(AppUserRepository.class),
                mock(FleetGroupRepository.class), mock(GroupDriverRepository.class), audit);
    }

    @Test
    void sosInbox_filteredToManagedGroups() {
        SosAlertRepository repo = mock(SosAlertRepository.class);
        FleetGroup g7 = new FleetGroup();
        g7.setId(7L);
        FleetGroup g8 = new FleetGroup();
        g8.setId(8L);
        SosAlert inScope = alertIn(7L);
        SosAlert outScope = alertIn(8L);
        when(repo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(inScope, outScope));
        when(groups.findByManager_IdOrderByCreatedAtDesc(42L)).thenReturn(List.of(managed(7L)));
        AppUserRepository users = mock(AppUserRepository.class);
        AppUser u = new AppUser();
        u.setId(42L);
        when(users.findByEmail("m@zp.test")).thenReturn(Optional.of(u));
        SosService svc = new SosService(repo, scope, users, groups,
                mock(GroupDriverRepository.class), AuditService.noop());
        login(Role.DISPATCHER, "m@zp.test");
        // Manager owns group 7 only.
        lenient().doNothing().when(scope).requireGroupAccess(argThat(gr -> gr != null && gr.getId() == 7L));
        lenient().doThrow(new AccessDeniedException("Not your group")).when(scope)
                .requireGroupAccess(argThat(gr -> gr != null && gr.getId() == 8L));

        var inbox = svc.listForDispatch();
        assertEquals(1, inbox.size());
    }

    @Test
    void sosResolve_setsResolvedAndAudits() {
        SosAlertRepository repo = mock(SosAlertRepository.class);
        SosAlert alert = alertIn(7L);
        alert.setStatus(SosStatus.OPEN);
        when(repo.findById(5L)).thenReturn(Optional.of(alert));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        AuditService audit = mock(AuditService.class);
        SosService svc = sosService(repo, audit);
        login(Role.DISPATCHER, "m@zp.test");
        var resolved = svc.resolve(5L, "false alarm");
        assertEquals("RESOLVED", resolved.getStatus());
        verify(audit).log(eq("m@zp.test"), eq("SOS_RESOLVE"),
                isNull(), isNull(), eq("false alarm"), isNull(), isNull());
    }

    @Test
    void sosResolve_driver_rejected() {
        SosService svc = sosService(mock(SosAlertRepository.class), AuditService.noop());
        login(Role.DRIVER, "d@zp.test");
        assertThrows(AccessDeniedException.class, () -> svc.resolve(5L, "x"));
    }

    private static FleetGroup managed(long id) {
        FleetGroup g = new FleetGroup();
        g.setId(id);
        return g;
    }

    private static SosAlert alertIn(long groupId) {
        SosAlert a = new SosAlert();
        a.setId(groupId);
        a.setStatus(SosStatus.OPEN);
        DispatchRoute r = mock(DispatchRoute.class);
        Tour t = mock(Tour.class);
        FleetGroup g = new FleetGroup();
        g.setId(groupId);
        lenient().when(t.getGroup()).thenReturn(g);
        lenient().when(r.getTour()).thenReturn(t);
        a.setRoute(r);
        Driver d = new Driver();
        d.setId(3L);
        a.setDriver(d);
        return a;
    }

    // ── SOS rate limit + proximity ───────────────────

    private DriverService driverServiceWith(SosAlertRepository sosRepo, AuditService audit,
                                            IdempotencyService idem) {
        return new DriverService(drivers, routes,
                mock(VehicleDocumentRepository.class), mock(TripSlipRepository.class),
                sosRepo, appUsers, mock(VehicleRepository.class),
                tourRepo, stopRepo,
                dutyLogs, mock(com.zonepilot.backend.auth.AuthService.class),
                audit, idem);
    }

    @Mock AppUserRepository appUsers;
    @Mock TourRepository tourRepo;
    @Mock TourStopRepository stopRepo;
    @Mock DutyLogRepository dutyLogs;

    @Test
    void sos_secondWithinMinute_rateLimited() {
        SosAlertRepository sosRepo = mock(SosAlertRepository.class);
        asDriver(appUsers, "sos@zp.test", 11L, 12L);
        SosAlert recent = new SosAlert();
        try {
            var f = SosAlert.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(recent, Instant.now().minusSeconds(10));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(sosRepo.findFirstByDriverIdOrderByCreatedAtDesc(12L))
                .thenReturn(Optional.of(recent));
        DriverService svc = driverServiceWith(sosRepo, AuditService.noop(),
                new IdempotencyService());
        assertThrows(RateLimitExceededException.class, () -> svc.sos(new SosRequest()));
    }

    @Test
    void sos_farFromAssignment_rejected() {
        SosAlertRepository sosRepo = mock(SosAlertRepository.class);
        when(sosRepo.findFirstByDriverIdOrderByCreatedAtDesc(12L))
                .thenReturn(Optional.empty());
        lenient().when(sosRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        AppUserRepository sosUsers = mock(AppUserRepository.class);
        asDriver(sosUsers, "sos2@zp.test", 11L, 12L);
        DispatchRoute route = mock(DispatchRoute.class);
        Driver d = new Driver();
        d.setId(12L);
        when(route.getDriver()).thenReturn(d);
        Tour tour = mock(Tour.class);
        when(tour.getId()).thenReturn(30L);
        when(route.getTour()).thenReturn(tour);
        when(routes.findById(9L)).thenReturn(Optional.of(route));
        TourStop stop = new TourStop();
        stop.setSeq(0);
        stop.setPoint(GF.createPoint(new Coordinate(77.62, 12.93)));
        stop.setStatus(StopStatus.PENDING);
        TourStopRepository stopRepo = mock(TourStopRepository.class);
        when(stopRepo.findByTourIdOrderBySeqAsc(30L)).thenReturn(List.of(stop));
        DriverService svc = new DriverService(drivers, routes,
                mock(VehicleDocumentRepository.class), mock(TripSlipRepository.class),
                sosRepo, sosUsers, mock(VehicleRepository.class),
                mock(TourRepository.class), stopRepo,
                mock(DutyLogRepository.class), mock(com.zonepilot.backend.auth.AuthService.class),
                AuditService.noop(), new IdempotencyService());
        SosRequest req = new SosRequest();
        req.setRouteId(9L);
        req.setLat(12.99);
        req.setLng(77.70); // ~12km from the stop
        assertThrows(ValidationException.class, () -> svc.sos(req));
    }

    private void asDriver(AppUserRepository users, String email, long userId, long driverId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new DepotPrincipal(email, null, Role.DRIVER), null, List.of()));
        AppUser u = new AppUser();
        u.setId(userId);
        lenient().when(users.findByEmail(email)).thenReturn(Optional.of(u));
        Driver d = new Driver();
        d.setId(driverId);
        d.setName("drv");
        lenient().when(drivers.findByUserId(userId)).thenReturn(Optional.of(d));
    }

    // ── PoD policy gates ────────────────────────────

    private TourService tourServiceWith(AuditService audit, IdempotencyService idem) {
        return new TourService(tours, stops, groups, vehicles, drivers, routes,
                scope, optimizer, compliance, driverService, positions,
                mock(GroupVehicleRepository.class), mock(GroupDriverRepository.class),
                audit, idem, new BCryptPasswordEncoder());
    }

    private Tour activeTourWithPinStop() {
        Vehicle v = new Vehicle();
        v.setId(21L);
        v.setRegistrationNumber("KA01-POD-21");
        v.setAvailability(VehicleAvailability.IN_TRANSIT);
        Driver d = new Driver();
        d.setId(22L);
        Tour t = new Tour();
        t.setId(23L);
        FleetGroup g = new FleetGroup();
        g.setId(4L);
        t.setGroup(g);
        t.setVehicle(v);
        t.setDriver(d);
        t.setStatus(TourStatus.ACTIVE);
        return t;
    }

    @Test
    void deliver_pinPolicy_wrongPin_rejected() {
        Tour tour = activeTourWithPinStop();
        TourStop stop = new TourStop();
        stop.setId(24L);
        stop.setTour(tour);
        stop.setSeq(0);
        stop.setPoint(GF.createPoint(new Coordinate(77.62, 12.93)));
        stop.setStatus(StopStatus.PENDING);
        stop.setPodPolicy(PodPolicy.PIN);
        stop.setPinHash(new BCryptPasswordEncoder().encode("4321"));
        lenient().when(tours.findByIdWithLock(23L)).thenReturn(Optional.of(tour));
        lenient().when(tours.findById(23L)).thenReturn(Optional.of(tour));
        lenient().when(stops.findById(24L)).thenReturn(Optional.of(stop));
        lenient().when(stops.findByTourIdOrderBySeqAsc(23L)).thenReturn(List.of(stop));
        login(Role.DISPATCHER, "m@zp.test");

        DeliverStopRequest req = new DeliverStopRequest();
        req.setLat(12.9305);
        req.setLng(77.6205);
        req.setPin("0000");
        assertThrows(ValidationException.class,
                () -> tourServiceWith(AuditService.noop(), new IdempotencyService())
                        .deliver(23L, 24L, req));
        assertEquals(StopStatus.PENDING, stop.getStatus());
    }

    @Test
    void deliver_photoPolicy_missingRef_rejected() {
        Tour tour = activeTourWithPinStop();
        TourStop stop = new TourStop();
        stop.setId(24L);
        stop.setTour(tour);
        stop.setSeq(0);
        stop.setPoint(GF.createPoint(new Coordinate(77.62, 12.93)));
        stop.setStatus(StopStatus.PENDING);
        stop.setPodPolicy(PodPolicy.PHOTO);
        lenient().when(tours.findByIdWithLock(23L)).thenReturn(Optional.of(tour));
        lenient().when(tours.findById(23L)).thenReturn(Optional.of(tour));
        lenient().when(stops.findById(24L)).thenReturn(Optional.of(stop));
        lenient().when(stops.findByTourIdOrderBySeqAsc(23L)).thenReturn(List.of(stop));
        login(Role.DISPATCHER, "m@zp.test");

        DeliverStopRequest req = new DeliverStopRequest();
        req.setLat(12.9305);
        req.setLng(77.6205);
        assertThrows(ValidationException.class,
                () -> tourServiceWith(AuditService.noop(), new IdempotencyService())
                        .deliver(23L, 24L, req));
    }

    @Test
    void deliver_failedStatus_badChip_rejected() {
        Tour tour = activeTourWithPinStop();
        TourStop stop = new TourStop();
        stop.setId(24L);
        stop.setTour(tour);
        stop.setSeq(0);
        stop.setPoint(GF.createPoint(new Coordinate(77.62, 12.93)));
        stop.setStatus(StopStatus.PENDING);
        lenient().when(tours.findByIdWithLock(23L)).thenReturn(Optional.of(tour));
        lenient().when(tours.findById(23L)).thenReturn(Optional.of(tour));
        lenient().when(stops.findById(24L)).thenReturn(Optional.of(stop));
        lenient().when(stops.findByTourIdOrderBySeqAsc(23L)).thenReturn(List.of(stop));
        login(Role.DISPATCHER, "m@zp.test");

        DeliverStopRequest req = new DeliverStopRequest();
        req.setLat(12.9305);
        req.setLng(77.6205);
        req.setStatus("FAILED");
        req.setReason("whatever dude");
        assertThrows(ValidationException.class,
                () -> tourServiceWith(AuditService.noop(), new IdempotencyService())
                        .deliver(23L, 24L, req));
    }

    @Test
    void deliver_managerEndpoint_rejectsDriver() {
        login(Role.DRIVER, "d@zp.test");
        assertThrows(AccessDeniedException.class, () ->
                tourServiceWith(AuditService.noop(), new IdempotencyService())
                        .deliver(23L, 24L, 12.93, 77.62));
    }

    @Test
    void approve_creatorCannotApprove() {
        Tour tour = activeTourWithPinStop();
        tour.setStatus(TourStatus.PENDING_APPROVAL);
        tour.setCreatedByUserId(42L);
        lenient().when(tours.findByIdWithLock(23L)).thenReturn(Optional.of(tour));
        lenient().when(tours.findById(23L)).thenReturn(Optional.of(tour));
        AppUserRepository users = mock(AppUserRepository.class);
        AppUser u = new AppUser();
        u.setId(42L);
        GroupScope scoping = mock(GroupScope.class);
        // currentUserId 42 == creator → separation-of-duties rejection.
        when(scoping.currentUserId()).thenReturn(Optional.of(42L));
        TourService svc = new TourService(tours, stops, groups, vehicles, drivers,
                routes, scoping, optimizer, compliance, driverService, positions,
                mock(GroupVehicleRepository.class), mock(GroupDriverRepository.class),
                AuditService.noop(), new IdempotencyService(), new BCryptPasswordEncoder());
        login(Role.DISPATCHER, "creator@zp.test");
        assertThrows(ValidationException.class, () -> svc.approve(23L));
    }

    @Test
    void deliver_duplicateOpId_conflicts() {
        Tour tour = activeTourWithPinStop();
        TourStop stop = new TourStop();
        stop.setId(24L);
        stop.setTour(tour);
        stop.setSeq(0);
        stop.setPoint(GF.createPoint(new Coordinate(77.62, 12.93)));
        stop.setStatus(StopStatus.PENDING);
        lenient().when(tours.findByIdWithLock(23L)).thenReturn(Optional.of(tour));
        lenient().when(tours.findById(23L)).thenReturn(Optional.of(tour));
        lenient().when(stops.findById(24L)).thenReturn(Optional.of(stop));
        lenient().when(stops.findByTourIdOrderBySeqAsc(23L)).thenReturn(List.of(stop));
        lenient().when(routes.findByTourIdOrderByIdDesc(23L)).thenReturn(List.of());
        login(Role.DISPATCHER, "m@zp.test");
        TourService svc = tourServiceWith(AuditService.noop(), new IdempotencyService());
        DeliverStopRequest req = new DeliverStopRequest();
        req.setLat(12.9305);
        req.setLng(77.6205);
        req.setOpId("pod-1");
        svc.deliver(23L, 24L, req);
        // Second stop would be needed for a true redeliver; the opId claim
        // itself must already conflict on a fresh call path:
        assertThrows(ConflictException.class, () -> svc.skip(23L, 24L, "OTHER:x", "pod-1"));
    }

    // ── ops: idempotency, purge, partitions ──────────

    @Test
    void idempotency_duplicateClaim_conflicts() {
        IdempotencyService idem = new IdempotencyService();
        assertTrue(idem.claim("a"));
        assertThrows(ConflictException.class, () -> idem.claim("a"));
    }

    @Test
    void replayPurge_deletesExpired() {
        VehiclePositionLogRepository repo = mock(VehiclePositionLogRepository.class);
        new ReplayPurgeService(repo, 30).purge();
        verify(repo).deleteByReplayTrueAndRecordedAtBefore(any(Instant.class));
    }

    @Test
    void partitionMonitor_reportsDefaultRows() {
        org.springframework.jdbc.core.JdbcTemplate jdbc =
                mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(7L);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("vehicle_position_log_2026_11"));
        PartitionMonitor.Report report = new PartitionMonitor(jdbc).report();
        assertEquals(7L, report.defaultRows());
        assertEquals("OK", report.status());
    }

    // ── duty annotate + 2-min correction ────────────

    private void ownTour(long tourId, long driverId) {
        Tour t = mock(Tour.class);
        Driver d = new Driver();
        d.setId(driverId);
        lenient().when(t.getId()).thenReturn(tourId);
        lenient().when(t.getDriver()).thenReturn(d);
        lenient().when(tourRepo.findById(tourId)).thenReturn(Optional.of(t));
    }

    @Test
    void dutyAnnotate_storesNote() {
        asDriver(appUsers, "duty@zp.test", 11L, 12L);
        ownTour(40L, 12L);
        DutyLog log = new DutyLog();
        log.setId(90L);
        Tour t = tourRepo.findById(40L).orElseThrow();
        log.setTour(t);
        Driver d = new Driver();
        d.setId(12L);
        log.setDriver(d);
        log.setDutyStatus(DutyStatus.ON_DUTY);
        log.setRecordedAt(Instant.now().minusSeconds(3600));
        when(dutyLogs.findById(90L)).thenReturn(Optional.of(log));
        when(dutyLogs.save(any())).thenAnswer(i -> i.getArgument(0));
        DriverService svc = driverServiceWith(mock(SosAlertRepository.class),
                AuditService.noop(), new IdempotencyService());
        var resp = svc.annotateDutyLog(40L, 90L, "waiting at gate");
        assertEquals("waiting at gate", resp.getNote());
    }

    @Test
    void dutyCorrection_afterTwoMinutes_rejected() {
        asDriver(appUsers, "duty2@zp.test", 11L, 12L);
        ownTour(41L, 12L);
        DutyLog log = new DutyLog();
        log.setId(91L);
        log.setTour(tourRepo.findById(41L).orElseThrow());
        Driver d = new Driver();
        d.setId(12L);
        log.setDriver(d);
        log.setDutyStatus(DutyStatus.ON_DUTY);
        log.setRecordedAt(Instant.now().minusSeconds(300));
        when(dutyLogs.findByTourIdOrderByRecordedAtAscIdAsc(41L)).thenReturn(List.of(log));
        DriverService svc = driverServiceWith(mock(SosAlertRepository.class),
                AuditService.noop(), new IdempotencyService());
        assertThrows(ValidationException.class,
                () -> svc.setTourDuty(41L, DutyStatus.BREAK, true, null));
    }

    // ── shift start/end semantics ──────────────────

    @Test
    void endShift_onTrip_rejected() {
        asDriver(appUsers, "shift@zp.test", 11L, 12L);
        when(routes.existsByDriverIdAndAssignmentStatusIn(eq(12L), anyList()))
                .thenReturn(true);
        DriverService svc = driverServiceWith(mock(SosAlertRepository.class),
                AuditService.noop(), new IdempotencyService());
        assertThrows(EligibilityException.class, svc::endShift);
    }

    @Test
    void startShift_marksActive() {
        asDriver(appUsers, "shift2@zp.test", 11L, 12L);
        DriverService svc = driverServiceWith(mock(SosAlertRepository.class),
                AuditService.noop(), new IdempotencyService());
        // currentDriver resolves from the drivers mock via asDriver stub.
        when(drivers.findByUserId(11L)).thenReturn(Optional.empty());
        Driver d = new Driver();
        d.setId(12L);
        d.setIsActive(false);
        when(drivers.findByUserId(11L)).thenReturn(Optional.of(d));
        when(drivers.save(any())).thenAnswer(i -> i.getArgument(0));
        var resp = svc.startShift();
        assertTrue(resp.getIsActive());
    }

    @Test
    void standDown_resolvesOwnSos() {
        asDriver(appUsers, "sos3@zp.test", 11L, 12L);
        SosAlertRepository sosRepo = mock(SosAlertRepository.class);
        SosAlert alert = new SosAlert();
        alert.setId(6L);
        Driver d = new Driver();
        d.setId(12L);
        alert.setDriver(d);
        alert.setStatus(SosStatus.OPEN);
        when(sosRepo.findById(6L)).thenReturn(Optional.of(alert));
        when(sosRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        DriverService svc = driverServiceWith(sosRepo, AuditService.noop(),
                new IdempotencyService());
        assertEquals(SosStatus.RESOLVED, svc.standDownSos(6L).getStatus());
    }

    // ── WS revalidate on SUBSCRIBE ─────────────────
}
