package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.AppUser;
import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.dto.request.SosRequest;
import com.zonepilot.backend.dto.response.SosAlertResponse;
import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.SosAlert;
import com.zonepilot.backend.enums.SosStatus;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * SOS with coords: offline queue replays carry the last-known fix plus
 * accuracy + stale flag; unknown location stays NULL (never 0,0) and the
 * dispatcher list flags it for the UI warning.
 */
@ExtendWith(MockitoExtension.class)
class SosCoordsTest {

    @Mock DriverRepository drivers;
    @Mock AppUserRepository users;
    @Mock SosAlertRepository sosAlerts;

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private void asDriver(String email, long userId, long driverId) {
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

    private DriverService driverService() {
        return new DriverService(drivers, mockRepo(DispatchRouteRepository.class),
                mockRepo(VehicleDocumentRepository.class), mockRepo(TripSlipRepository.class),
                sosAlerts, users, mockRepo(VehicleRepository.class),
                mockRepo(TourRepository.class), mockRepo(TourStopRepository.class),
                mockRepo(DutyLogRepository.class),
                org.mockito.Mockito.mock(com.zonepilot.backend.auth.AuthService.class));
    }

    @SuppressWarnings("unchecked")
    private static <T> T mockRepo(Class<T> type) {
        return org.mockito.Mockito.mock(type);
    }

    private static SosRequest sos(Double lat, Double lng, Double accuracyM, Boolean stale) {
        SosRequest req = new SosRequest();
        req.setLat(lat);
        req.setLng(lng);
        req.setAccuracyM(accuracyM);
        req.setStale(stale);
        return req;
    }

    @Test
    void sos_storesFixAccuracyAndStale() {
        asDriver("fix@zp.test", 11L, 12L);
        when(sosAlerts.save(any())).thenAnswer(i -> i.getArgument(0));
        // In-bounds fix (Koramangala).
        SosAlert saved = driverService().sos(sos(12.9352, 77.6245, 12.5, true));

        assertNotNull(saved.getLocation());
        assertEquals(12.5, saved.getAccuracyM());
        assertTrue(saved.getStale());
    }

    @Test
    void sos_unknownLocation_staysNullWithStaleFlag() {
        asDriver("unknown@zp.test", 11L, 12L);
        when(sosAlerts.save(any())).thenAnswer(i -> i.getArgument(0));

        SosAlert saved = driverService().sos(sos(null, null, null, true));

        assertNull(saved.getLocation());
        assertNull(saved.getAccuracyM());
        assertTrue(saved.getStale());
    }

    @Test
    void sos_outOfBounds_stillRejected() {
        asDriver("oob@zp.test", 11L, 12L);
        assertThrows(ValidationException.class,
                () -> driverService().sos(sos(0.0, 0.0, 5.0, false)));
    }

    @Test
    void sosList_flagsUnknownAndStaleForDispatcher() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new DepotPrincipal("mgr@zp.test", null, Role.OWNER), null, List.of()));
        SosAlert withFix = new SosAlert();
        withFix.setId(1L);
        withFix.setStatus(SosStatus.OPEN);
        withFix.setAccuracyM(8.0);
        withFix.setStale(false);
        org.locationtech.jts.geom.GeometryFactory gf =
                new org.locationtech.jts.geom.GeometryFactory(
                        new org.locationtech.jts.geom.PrecisionModel(), 4326);
        withFix.setLocation(gf.createPoint(
                new org.locationtech.jts.geom.Coordinate(77.6245, 12.9352)));
        SosAlert unknown = new SosAlert();
        unknown.setId(2L);
        unknown.setStatus(SosStatus.OPEN);
        unknown.setStale(true);
        when(sosAlerts.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(withFix, unknown));
        SosService sos = new SosService(sosAlerts);

        List<SosAlertResponse> list = sos.listForDispatch();

        assertEquals(2, list.size());
        assertFalse(list.get(0).isUnknown());
        assertFalse(list.get(0).isStale());
        assertEquals(12.9352, list.get(0).getLat());
        assertTrue(list.get(1).isUnknown());
        assertTrue(list.get(1).isStale());
        assertNull(list.get(1).getLat());
    }

    @Test
    void sosList_driverForbidden() {
        asDriver("drv@zp.test", 11L, 12L);
        SosService sos = new SosService(sosAlerts);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                sos::listForDispatch);
    }

    private static Instant now() {
        return Instant.now();
    }
}
