package com.zonepilot.backend.service;

import com.zonepilot.backend.dto.request.CreateZoneRequest;
import com.zonepilot.backend.dto.response.ZoneResponse;
import com.zonepilot.backend.entity.ZoneRestriction;
import com.zonepilot.backend.enums.GeometryType;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.ZoneRestrictionRepository;
import com.zonepilot.backend.repository.ZoneRestrictionRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** RED: ZoneService corridor/circle validation + new-field wiring. */
@ExtendWith(MockitoExtension.class)
class CorridorZoneValidationTest {

    private static final String POLYGON_GEOJSON =
            "{\"type\":\"Polygon\",\"coordinates\":[[[77.6,12.9],[77.61,12.9],[77.61,12.91],[77.6,12.91],[77.6,12.9]]]}";

    @Mock private ZoneRestrictionRepository zoneRepository;
    @Mock private ZoneRestrictionRuleRepository ruleRepository;

    private ZoneService service;

    @BeforeEach
    void setUp() {
        service = new ZoneService(zoneRepository, ruleRepository);
    }

    private CreateZoneRequest request(String geometryType) {
        CreateZoneRequest req = new CreateZoneRequest();
        req.setName("Z");
        req.setBoundaryGeoJson(POLYGON_GEOJSON);
        req.setRestrictionType("NO_ENTRY");
        req.setGeometryType(geometryType);
        return req;
    }

    @Test
    void corridorMissingWidth_rejected() {
        CreateZoneRequest req = request("CORRIDOR");
        req.setCorridorRouteId(500L);
        assertThrows(ValidationException.class, () -> service.createZone(req));
    }

    @Test
    void corridorMissingRoute_rejected() {
        CreateZoneRequest req = request("CORRIDOR");
        req.setCorridorWidthM(50.0);
        assertThrows(ValidationException.class, () -> service.createZone(req));
    }

    @Test
    void circleMissingRadius_rejected() {
        assertThrows(ValidationException.class, () -> service.createZone(request("CIRCLE")));
    }

    @Test
    void polygonDefaults_wiredThrough() {
        when(zoneRepository.save(any(ZoneRestriction.class))).thenAnswer(inv -> {
            ZoneRestriction z = inv.getArgument(0);
            z.setId(3L);
            return z;
        });
        when(ruleRepository.findByZoneIdAndIsActive(3L, true)).thenReturn(Collections.emptyList());

        ZoneResponse response = service.createZone(request(null));

        assertEquals(GeometryType.POLYGON, response.getGeometryType());
    }
}
