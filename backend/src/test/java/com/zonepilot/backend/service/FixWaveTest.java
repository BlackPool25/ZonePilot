package com.zonepilot.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zonepilot.backend.auth.AppUser;
import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.dto.request.CreateSlipRequest;
import com.zonepilot.backend.dto.request.CreateTourRequest;
import com.zonepilot.backend.dto.request.SosRequest;
import com.zonepilot.backend.dto.response.PositionRecordResponse;
import com.zonepilot.backend.dto.response.TourResponse;
import com.zonepilot.backend.entity.DispatchRoute;
import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.DutyLog;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.PositionQuarantine;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.TourStatus;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.entity.VehiclePositionLog;
import com.zonepilot.backend.entity.ZoneRestriction;
import com.zonepilot.backend.entity.ZoneRestrictionRule;
import com.zonepilot.backend.enums.AssignmentStatus;
import com.zonepilot.backend.enums.DutyStatus;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.enums.VehicleAvailability;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.exception.EligibilityException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.repository.DriverRepository;
import com.zonepilot.backend.repository.DutyLogRepository;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.repository.GroupDriverRepository;
import com.zonepilot.backend.repository.GroupVehicleRepository;
import com.zonepilot.backend.repository.PositionQuarantineRepository;
import com.zonepilot.backend.repository.SosAlertRepository;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.TourStopRepository;
import com.zonepilot.backend.repository.TripSlipRepository;
import com.zonepilot.backend.repository.VehicleDocumentRepository;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import com.zonepilot.backend.repository.ZoneBreachLogRepository;
import com.zonepilot.backend.repository.ZoneRestrictionRepository;
import com.zonepilot.backend.repository.ZoneRestrictionRuleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fix wave (backend): RED tests for bindKeys forgery, cross-group create,
 * replay paging/idempotency, breach dedupe counter, HTTP duplicate 200,
 * availability double-book, bounded login map, COMPLETED resolve, explicit
 * breach mapping, duty throttle, WS /app allow-list + suffix accept,
 * active-only paged myTours. Guards (SOS bbox, SlipKind wrap) included.
 */
@ExtendWith(MockitoExtension.class)
class FixWaveTest {

    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);

    @Mock private VehicleRepository vehicleRepository;
    @Mock private VehiclePositionLogRepository positionLogRepository;
    @Mock private DispatchRouteRepository dispatchRouteRepository;
    @Mock private TourRepository tourRepository;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ApplicationEventPublisher events;
    @Mock private TickBroadcaster broadcaster;
    @Mock private DriverRepository driverRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private GroupScope scope;

    @BeforeEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearAuthAfter() {
        SecurityContextHolder.clearContext();
    }

    private Vehicle activeVehicle(long id) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setRegistrationNumber("KA01-FIX-" + id);
        v.setVehicleClass(VehicleClass.LCV);
        v.setOwnerName("o");
        v.setIsActive(true);
        return v;
    }

    private Tour tourInGroup(long tourId, long groupId, long driverId, TourStatus status) {
        FleetGroup g = new FleetGroup();
        g.setId(groupId);
        g.setName("g" + groupId);
        Driver d = new Driver();
        d.setId(driverId);
        d.setName("d" + driverId);
        Vehicle v = activeVehicle(11L);
        Tour t = new Tour();
        t.setId(tourId);
        t.setGroup(g);
        t.setVehicle(v);
        t.setDriver(d);
        t.setStatus(status);
        t.setDutyStatus(DutyStatus.OFF_DUTY);
        return t;
    }

    // ── 1. bindKeys: forged groupId on MQTT path (null caller) rejected ──

    @Test
    void mqttForgedGroup_rejected() {
        when(vehicleRepository.findById(1L)).thenReturn(Optional.of(activeVehicle(1L)));
        when(tourRepository.findById(3L)).thenReturn(Optional.of(tourInGroup(3L, 7L, 9L, TourStatus.ACTIVE)));
        PositionTrackingService service = new PositionTrackingService(vehicleRepository,
                positionLogRepository, dispatchRouteRepository, tourRepository, jdbcTemplate,
                events, broadcaster, driverRepository, appUserRepository, scope,
                org.mockito.Mockito.mock(com.zonepilot.backend.repository.FleetGroupRepository.class),
                org.mockito.Mockito.mock(com.zonepilot.backend.repository.GroupVehicleRepository.class));

        ValidationException e = assertThrows(ValidationException.class, () ->
                service.recordPosition(1L, 9L, 3L, 999L, 12.97, 77.59,
                        Instant.now(), null, null, PositionSource.TRACCAR));
        assertTrue(e.getMessage().contains("groupId"));
    }

    // ── 9a. resolveTour: COMPLETED tour rejected ──

    @Test
    void completedTour_rejected() {
        when(vehicleRepository.findById(1L)).thenReturn(Optional.of(activeVehicle(1L)));
        when(tourRepository.findById(3L)).thenReturn(Optional.of(tourInGroup(3L, 7L, 9L, TourStatus.COMPLETED)));
        PositionTrackingService service = new PositionTrackingService(vehicleRepository,
                positionLogRepository, dispatchRouteRepository, tourRepository, jdbcTemplate,
                events, broadcaster, driverRepository, appUserRepository, scope,
                org.mockito.Mockito.mock(com.zonepilot.backend.repository.FleetGroupRepository.class),
                org.mockito.Mockito.mock(com.zonepilot.backend.repository.GroupVehicleRepository.class));

        assertThrows(ValidationException.class, () ->
                service.recordPosition(1L, 9L, 3L, 7L, 12.97, 77.59,
                        Instant.now(), null, null, PositionSource.TRACCAR));
    }

    // ── 2/6. TourService.create: cross-group + busy vehicle ──

    private TourService tourServiceWith(GroupVehicleRepository gv, GroupDriverRepository gd,
                                       FleetGroupRepository groups, VehicleRepository vehicles,
                                       DriverRepository drivers, DispatchRouteRepository routes,
                                       GroupScope sc, DriverService ds) {
        return new TourService(tourRepository, mock(TourStopRepository.class), groups,
                vehicles, drivers, routes, sc, mock(TourOptimizerService.class),
                mock(RouteComplianceService.class), ds, mock(PositionTrackingService.class),
                gv, gd);
    }

    private CreateTourRequest tourReq(long groupId, long vehicleId, long driverId) {
        CreateTourRequest req = new CreateTourRequest();
        req.setGroupId(groupId);
        req.setVehicleId(vehicleId);
        req.setDriverId(driverId);
        req.setOriginLat(12.97);
        req.setOriginLng(77.59);
        CreateTourRequest.StopInput s = new CreateTourRequest.StopInput();
        s.setLat(12.98);
        s.setLng(77.60);
        req.setStops(List.of(s));
        return req;
    }

    @Test
    void tourCreate_crossGroupVehicle_rejected403() {
        FleetGroupRepository groups = mock(FleetGroupRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        DriverRepository drivers = mock(DriverRepository.class);
        GroupVehicleRepository gv = mock(GroupVehicleRepository.class);
        GroupDriverRepository gd = mock(GroupDriverRepository.class);
        FleetGroup g = new FleetGroup();
        g.setId(5L);
        g.setName("b");
        Vehicle v = activeVehicle(11L);
        Driver d = new Driver();
        d.setId(21L);
        when(groups.findById(5L)).thenReturn(Optional.of(g));
        when(vehicles.findById(11L)).thenReturn(Optional.of(v));
        when(drivers.findById(21L)).thenReturn(Optional.of(d));
        when(gv.existsByGroupIdAndVehicleId(5L, 11L)).thenReturn(false);
        lenient().when(gd.existsByGroupIdAndDriverId(5L, 21L)).thenReturn(true);
        TourService service = tourServiceWith(gv, gd, groups, vehicles, drivers,
                mock(DispatchRouteRepository.class), mock(GroupScope.class),
                mock(DriverService.class));

        assertThrows(AccessDeniedException.class,
                () -> service.create(tourReq(5L, 11L, 21L)));
    }

    @Test
    void tourCreate_busyVehicle_rejected() {
        FleetGroupRepository groups = mock(FleetGroupRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        DriverRepository drivers = mock(DriverRepository.class);
        GroupVehicleRepository gv = mock(GroupVehicleRepository.class);
        GroupDriverRepository gd = mock(GroupDriverRepository.class);
        FleetGroup g = new FleetGroup();
        g.setId(5L);
        g.setName("b");
        Vehicle v = activeVehicle(11L);
        v.setAvailability(VehicleAvailability.IN_TRANSIT);
        Driver d = new Driver();
        d.setId(21L);
        when(groups.findById(5L)).thenReturn(Optional.of(g));
        when(vehicles.findById(11L)).thenReturn(Optional.of(v));
        when(drivers.findById(21L)).thenReturn(Optional.of(d));
        when(gv.existsByGroupIdAndVehicleId(5L, 11L)).thenReturn(true);
        when(gd.existsByGroupIdAndDriverId(5L, 21L)).thenReturn(true);
        TourService service = tourServiceWith(gv, gd, groups, vehicles, drivers,
                mock(DispatchRouteRepository.class), mock(GroupScope.class),
                mock(DriverService.class));

        assertThrows(EligibilityException.class,
                () -> service.create(tourReq(5L, 11L, 21L)));
    }

    // ── 3. ReplayService: paged latest-2000 + idempotent retry ──

    private ReplayService replayService(VehiclePositionLogRepository positions) {
        PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
        lenient().when(txm.getTransaction(any()))
                .thenAnswer(i -> mock(TransactionStatus.class));
        return new ReplayService(tourRepository, positions, mock(PositionTrackingService.class),
                scope, broadcaster, txm);
    }

    private List<VehiclePositionLog> historyDesc(int n) {
        List<VehiclePositionLog> out = new ArrayList<>(n);
        Instant base = Instant.parse("2026-10-01T10:00:00Z");
        for (int i = n - 1; i >= 0; i--) {
            VehiclePositionLog log = new VehiclePositionLog();
            log.setVehicleId(11L);
            log.setDriverId(21L);
            log.setTourId(9L);
            log.setGroupId(31L);
            log.setPosition(gf.createPoint(new Coordinate(77.6 + i * 0.001, 12.9 + i * 0.001)));
            log.setRecordedAt(base.plusSeconds(i * 10L));
            log.setSpeedKmh(BigDecimal.valueOf(30));
            log.setHeadingDeg((short) 90);
            log.setSource(PositionSource.LIVE);
            out.add(log);
        }
        return out;
    }

    @Test
    void replay_usesPagedTailQuery_notFullLoad() {
        VehiclePositionLogRepository positions = mock(VehiclePositionLogRepository.class);
        ReplayService replay = replayService(positions);
        when(tourRepository.findById(9L)).thenReturn(Optional.of(tourInGroup(9L, 31L, 21L, TourStatus.ACTIVE)));
        when(positions.findTop2000ByTourIdOrderByRecordedAtDesc(9L)).thenReturn(historyDesc(3));

        ReplayService.ReplayResult result = replay.start(9L, 5);

        assertEquals(3, result.points());
        verify(positions, never()).findByTourIdOrderByRecordedAtAsc(any());
    }

    @Test
    void replay_retry_sameBase_sameStamps() {
        PositionTrackingService tracking = mock(PositionTrackingService.class);
        PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
        lenient().when(txm.getTransaction(any()))
                .thenAnswer(i -> mock(TransactionStatus.class));
        VehiclePositionLogRepository positions = mock(VehiclePositionLogRepository.class);
        ReplayService replay = new ReplayService(tourRepository, positions, tracking,
                scope, broadcaster, txm);
        when(tourRepository.findById(9L)).thenReturn(Optional.of(tourInGroup(9L, 31L, 21L, TourStatus.ACTIVE)));
        when(positions.findTop2000ByTourIdOrderByRecordedAtDesc(9L)).thenReturn(historyDesc(3));
        Instant endAt = Instant.now().minusSeconds(30);

        replay.start(9L, 5, endAt);
        ArgumentCaptor<Instant> first = ArgumentCaptor.forClass(Instant.class);
        verify(tracking, times(3)).recordReplayFix(any(), any(), any(), any(),
                anyDouble(), anyDouble(), first.capture(), any(), any(), any());
        clearInvocations(tracking);
        replay.resetRateLimits(); // retry-after-window: same stamps, no second copy

        replay.start(9L, 5, endAt);
        ArgumentCaptor<Instant> second = ArgumentCaptor.forClass(Instant.class);
        verify(tracking, times(3)).recordReplayFix(any(), any(), any(), any(),
                anyDouble(), anyDouble(), second.capture(), any(), any(), any());

        assertEquals(first.getAllValues(), second.getAllValues());
    }

    // ── 4. BreachWorker: duplicate open breach guarded + counted ──

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void breach_duplicateCreate_countedNotThrown() {
        ZoneBreachLogRepository breachLogs = mock(ZoneBreachLogRepository.class);
        VehicleRepository vRepo = mock(VehicleRepository.class);
        ZoneRestrictionRepository zRepo = mock(ZoneRestrictionRepository.class);
        ZoneRestrictionRuleRepository rRepo = mock(ZoneRestrictionRuleRepository.class);
        BreachWorker worker = new BreachWorker(breachLogs, vRepo, zRepo, rRepo,
                mock(BreachService.class), jdbcTemplate, new FakeIngestState());
        Vehicle vehicle = activeVehicle(5L);
        ZoneRestriction zone = new ZoneRestriction();
        zone.setId(1L);
        zone.setName("z");
        ZoneRestrictionRule rule = new ZoneRestrictionRule();
        rule.setId(9L);
        lenient().when(vRepo.findById(5L)).thenReturn(Optional.of(vehicle));
        lenient().when(zRepo.findById(1L)).thenReturn(Optional.of(zone));
        lenient().when(rRepo.findById(9L)).thenReturn(Optional.of(rule));
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{1L, 9L, "NO_ENTRY"});
        when(jdbcTemplate.query(argThat((String sql) -> sql.contains("ST_Covers")),
                any(RowMapper.class), anyDouble(), anyDouble(), anyString(), anyInt(),
                any(java.sql.Time.class), any(java.sql.Time.class), any(java.sql.Time.class)))
                .thenReturn((List) rows);
        lenient().when(breachLogs.findOpenBreach(any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(breachLogs.findOpenBreachForUpdate(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(breachLogs.save(any())).thenThrow(new DataIntegrityViolationException("dup"));

        long before = BreachWorker.duplicateRejectedCount();
        assertDoesNotThrow(() -> worker.onEvent(
                new BreachWorker.PositionFix(5L, 100L, 12.9352, 77.6245,
                        Instant.parse("2026-10-20T08:00:00Z"), PositionSource.LIVE)));
        assertTrue(BreachWorker.duplicateRejectedCount() > before);
    }

    // ── 9b. mapBreachType: unknown restriction type creates no row ──

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void breach_unknownRestrictionType_noRow() {
        ZoneBreachLogRepository breachLogs = mock(ZoneBreachLogRepository.class);
        VehicleRepository vRepo = mock(VehicleRepository.class);
        ZoneRestrictionRepository zRepo = mock(ZoneRestrictionRepository.class);
        ZoneRestrictionRuleRepository rRepo = mock(ZoneRestrictionRuleRepository.class);
        BreachWorker worker = new BreachWorker(breachLogs, vRepo, zRepo, rRepo,
                mock(BreachService.class), jdbcTemplate, new FakeIngestState());
        Vehicle vehicle = activeVehicle(5L);
        ZoneRestriction zone = new ZoneRestriction();
        zone.setId(1L);
        lenient().when(vRepo.findById(5L)).thenReturn(Optional.of(vehicle));
        lenient().when(zRepo.findById(1L)).thenReturn(Optional.of(zone));
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{1L, 9L, "BOGUS_TYPE"});
        when(jdbcTemplate.query(argThat((String sql) -> sql.contains("ST_Covers")),
                any(RowMapper.class), anyDouble(), anyDouble(), anyString(), anyInt(),
                any(java.sql.Time.class), any(java.sql.Time.class), any(java.sql.Time.class)))
                .thenReturn((List) rows);
        lenient().when(breachLogs.findOpenBreach(any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(breachLogs.findOpenBreachForUpdate(any(), any(), any()))
                .thenReturn(Optional.empty());

        worker.onEvent(new BreachWorker.PositionFix(5L, 100L, 12.9352, 77.6245,
                Instant.parse("2026-10-20T08:00:00Z"), PositionSource.LIVE));

        verify(breachLogs, never()).save(any());
    }

    // ── 5. TraccarIngest HTTP duplicate → 200; bad ts quarantined ──

    private TraccarIngestService ingestService(FakeIngestState state,
                                              PositionQuarantineRepository quarantine,
                                              PositionTrackingService positions) {
        return new TraccarIngestService(vehicleRepository, positions,
                new QualityFilter(state), state, quarantine, new ObjectMapper());
    }

    @Test
    void httpRetry_storedFix_returns200() {
        FakeIngestState state = new FakeIngestState();
        PositionQuarantineRepository quarantine = mock(PositionQuarantineRepository.class);
        PositionTrackingService positions = mock(PositionTrackingService.class);
        when(vehicleRepository.findById(7L)).thenReturn(Optional.of(activeVehicle(7L)));
        when(positions.recordPosition(anyLong(), any(), any(), any(), anyDouble(), anyDouble(),
                any(), any(), any(), any())).thenAnswer(inv -> {
            PositionRecordResponse r = new PositionRecordResponse();
            PositionRecordResponse.PositionDetail d = new PositionRecordResponse.PositionDetail();
            d.setLatitude(inv.getArgument(4));
            d.setLongitude(inv.getArgument(5));
            d.setRecordedAt(inv.getArgument(6));
            d.setSource(PositionSource.TRACCAR.name());
            r.setPosition(d);
            return r;
        });
        TraccarIngestService ingest = ingestService(state, quarantine, positions);
        Instant ts = Instant.now().minusSeconds(60);

        PositionRecordResponse first = ingest.ingest(7L, 12.97, 77.59, ts, null, null,
                null, PositionSource.TRACCAR, null, null, null);
        assertNotNull(first);
        PositionRecordResponse second = assertDoesNotThrow(() ->
                ingest.ingest(7L, 12.97, 77.59, ts, null, null,
                        null, PositionSource.TRACCAR, null, null, null));
        assertEquals(ts, second.getPosition().getRecordedAt());
        verify(quarantine, never()).save(any());
    }

    @Test
    void mqttBadTs_quarantinedNotNow() {
        FakeIngestState state = new FakeIngestState();
        PositionQuarantineRepository quarantine = mock(PositionQuarantineRepository.class);
        PositionTrackingService positions = mock(PositionTrackingService.class);
        when(vehicleRepository.findById(7L)).thenReturn(Optional.of(activeVehicle(7L)));
        TraccarIngestService ingest = ingestService(state, quarantine, positions);

        ingest.handle("zonepilot/fleet/vehicles/7/position",
                "{\"vehicleId\":7,\"lat\":12.97,\"lon\":77.59,\"ts\":\"not-a-time\"}");

        verify(quarantine, times(1)).save(argThat((PositionQuarantine q) ->
                "BAD_TS".equals(q.getReason())));
        verify(positions, never()).recordPosition(anyLong(), any(), any(), any(),
                anyDouble(), anyDouble(), any(), any(), any(), any());
    }

    // ── 7. Auth login map bounded ──

    @Test
    void loginMap_boundedUnderFlood() throws Exception {
        AppUserRepository users = mock(AppUserRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        com.zonepilot.backend.auth.AuthService auth =
                new com.zonepilot.backend.auth.AuthService(users, passwords,
                        mock(com.zonepilot.backend.auth.JwtService.class));

        for (int i = 0; i < 10_500; i++) {
            String email = "flood" + i + "@x.test";
            assertThrows(BadCredentialsException.class,
                    () -> auth.login(email, "wrongpass"));
        }
        Field f = com.zonepilot.backend.auth.LoginThrottleService.class.getDeclaredField("memory");
        f.setAccessible(true);
        Field tf = com.zonepilot.backend.auth.AuthService.class.getDeclaredField("throttle");
        tf.setAccessible(true);
        com.zonepilot.backend.auth.LoginThrottleService throttle =
                (com.zonepilot.backend.auth.LoginThrottleService) tf.get(auth);
        Map<?, ?> buckets = (Map<?, ?>) f.get(throttle);
        assertTrue(buckets.size() <= 10_000,
                "login bucket map grew to " + buckets.size());
    }

    // ── 9c. Duty spam throttle + OFF_DUTY keeps vehicle held ──

    private DriverService driverServiceWith(DriverRepository drivers, AppUserRepository users,
                                            TourRepository tours, DutyLogRepository dutyLogs) {
        return new DriverService(drivers, mock(DispatchRouteRepository.class),
                mock(VehicleDocumentRepository.class), mock(TripSlipRepository.class),
                mock(SosAlertRepository.class), users, mock(VehicleRepository.class),
                tours, mock(TourStopRepository.class), dutyLogs,
                mock(com.zonepilot.backend.auth.AuthService.class));
    }

    private void asDriver(String email, long userId, long driverId,
                          AppUserRepository users, DriverRepository drivers) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new DepotPrincipal(email, null, Role.DRIVER), null, List.of()));
        AppUser u = new AppUser();
        u.setId(userId);
        lenient().when(users.findByEmail(email)).thenReturn(Optional.of(u));
        Driver d = new Driver();
        d.setId(driverId);
        d.setName("d");
        lenient().when(drivers.findByUserId(userId)).thenReturn(Optional.of(d));
    }

    @Test
    void dutySpam_rejected() {
        DriverRepository drivers = mock(DriverRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        TourRepository tours = mock(TourRepository.class);
        DutyLogRepository dutyLogs = mock(DutyLogRepository.class);
        asDriver("spam@zp.test", 11L, 12L, users, drivers);
        Tour tour = tourInGroup(14L, 3L, 12L, TourStatus.ACTIVE);
        when(tours.findById(14L)).thenReturn(Optional.of(tour));
        DutyLog recent = new DutyLog();
        recent.setRecordedAt(Instant.now());
        lenient().when(dutyLogs.findByTourIdOrderByRecordedAtAscIdAsc(14L))
                .thenReturn(List.of(recent));
        DriverService service = driverServiceWith(drivers, users, tours, dutyLogs);

        assertThrows(ValidationException.class,
                () -> service.setTourDuty(14L, DutyStatus.DRIVING));
        verify(dutyLogs, never()).save(any());
    }

    @Test
    void offDuty_midTour_keepsVehicleHeld() {
        DriverRepository drivers = mock(DriverRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        TourRepository tours = mock(TourRepository.class);
        DutyLogRepository dutyLogs = mock(DutyLogRepository.class);
        asDriver("held@zp.test", 11L, 12L, users, drivers);
        Tour tour = tourInGroup(14L, 3L, 12L, TourStatus.ACTIVE);
        tour.getVehicle().setAvailability(VehicleAvailability.RESERVED);
        when(tours.findById(14L)).thenReturn(Optional.of(tour));
        lenient().when(dutyLogs.findByTourIdOrderByRecordedAtAscIdAsc(14L))
                .thenReturn(List.of());
        DriverService service = driverServiceWith(drivers, users, tours, dutyLogs);

        service.setTourDuty(14L, DutyStatus.OFF_DUTY);

        assertEquals(VehicleAvailability.RESERVED, tour.getVehicle().getAvailability());
    }

    // ── 9d. WS: /app allow-list + group suffix accept ──

    private com.zonepilot.backend.config.WsAuthInterceptor wsInterceptor(
            FleetGroupRepository groups, GroupScope sc) {
        return new com.zonepilot.backend.config.WsAuthInterceptor(
                mock(com.zonepilot.backend.auth.JwtService.class), groups, sc,
                mock(AppUserRepository.class), mock(DriverRepository.class),
                mock(GroupDriverRepository.class),
                mock(org.springframework.messaging.SubscribableChannel.class),
                mock(org.springframework.messaging.simp.SimpMessagingTemplate.class),
                mock(com.zonepilot.backend.service.TickBroadcaster.class));
    }

    private Message<?> frame(StompCommand cmd, String dest, DepotPrincipal principal) {
        StompHeaderAccessor h = StompHeaderAccessor.create(cmd);
        h.setDestination(dest);
        h.setSessionId("s1");
        if (principal != null) {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("zp-principal", principal);
            h.setSessionAttributes(attrs);
        }
        return MessageBuilder.createMessage(new byte[0], h.getMessageHeaders());
    }

    @Test
    void wsSend_unknownAppDestination_rejected() {
        com.zonepilot.backend.config.WsAuthInterceptor ws =
                wsInterceptor(mock(FleetGroupRepository.class), mock(GroupScope.class));

        Message<?> out = ws.preSend(
                frame(StompCommand.SEND, "/app/evil",
                        new DepotPrincipal("o@zp.test", null, Role.OWNER)),
                mock(org.springframework.messaging.MessageChannel.class));

        assertNull(out);
    }

    @Test
    void wsSubscribe_groupSuffix_accepted() {
        FleetGroupRepository groups = mock(FleetGroupRepository.class);
        FleetGroup g = new FleetGroup();
        g.setId(7L);
        when(groups.findById(7L)).thenReturn(Optional.of(g));
        com.zonepilot.backend.config.WsAuthInterceptor ws =
                wsInterceptor(groups, mock(GroupScope.class));

        Message<?> out = ws.preSend(
                frame(StompCommand.SUBSCRIBE, "/topic/group/7/ticks",
                        new DepotPrincipal("o@zp.test", null, Role.OWNER)),
                mock(org.springframework.messaging.MessageChannel.class));

        assertNotNull(out);
    }

    // ── 9e. myTours active-only paging ──

    @Test
    void myActiveTours_paged_activeOnly() {
        DriverRepository drivers = mock(DriverRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        TourRepository tours = mock(TourRepository.class);
        asDriver("d@zp.test", 7L, 3L, users, drivers);
        Tour active = tourInGroup(1L, 4L, 3L, TourStatus.ACTIVE);
        Tour done = tourInGroup(2L, 4L, 3L, TourStatus.COMPLETED);
        when(tours.findByDriverIdAndStatusInOrderByIdDesc(eq(3L), anyCollection(), any()))
                .thenReturn(new PageImpl<>(List.of(active, done)));
        DriverService service = driverServiceWith(drivers, users, tours,
                mock(DutyLogRepository.class));

        Page<TourResponse> page = service.myActiveTours(PageRequest.of(0, 20));

        assertFalse(page.getContent().isEmpty());
        assertTrue(page.getContent().stream()
                .noneMatch(t -> "COMPLETED".equals(t.getStatus())));
    }

    // ── Guards: SOS bbox + SlipKind wrap (already enforced, pinned) ──

    @Test
    void sos_outsideBounds_rejected() {
        DriverRepository drivers = mock(DriverRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        asDriver("sos@zp.test", 11L, 12L, users, drivers);
        DriverService service = driverServiceWith(drivers, users,
                mock(TourRepository.class), mock(DutyLogRepository.class));
        SosRequest req = new SosRequest();
        req.setLat(0.0);
        req.setLng(0.0);

        assertThrows(ValidationException.class, () -> service.sos(req));
    }

    @Test
    void slip_unknownKind_rejectedWithKnownKinds() {
        DriverRepository drivers = mock(DriverRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        DispatchRouteRepository routes = mock(DispatchRouteRepository.class);
        asDriver("slip@zp.test", 11L, 12L, users, drivers);
        Driver d = new Driver();
        d.setId(12L);
        DispatchRoute route = new DispatchRoute();
        route.setId(50L);
        route.setDriver(d);
        route.setAssignmentStatus(AssignmentStatus.IN_TRANSIT);
        when(routes.findById(50L)).thenReturn(Optional.of(route));
        DriverService service = new DriverService(drivers, routes,
                mock(VehicleDocumentRepository.class), mock(TripSlipRepository.class),
                mock(SosAlertRepository.class), users, mock(VehicleRepository.class),
                mock(TourRepository.class), mock(TourStopRepository.class),
                mock(DutyLogRepository.class),
                mock(com.zonepilot.backend.auth.AuthService.class));
        CreateSlipRequest req = new CreateSlipRequest();
        req.setKind("NOPE");
        req.setAmount(10.0);

        ValidationException e = assertThrows(ValidationException.class,
                () -> service.slip(50L, req));
        assertTrue(e.getMessage().contains("FUEL"));
    }
}
