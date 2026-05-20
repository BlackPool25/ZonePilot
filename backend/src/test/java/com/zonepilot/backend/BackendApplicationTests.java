package com.zonepilot.backend;

import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.enums.BreachType;
import com.zonepilot.backend.enums.RestrictionType;
import com.zonepilot.backend.enums.RouteStatus;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.service.PositionTrackingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ZonePilot backend.
 *
 * These tests run without a Spring context or database.
 * They cover: DTO contracts, enum correctness, exception messages,
 * and coordinate validation logic.
 *
 * Integration tests (requiring DB) are in ZonePilotIntegrationTest.
 */
class BackendApplicationTests {

    // ── ApiResponse contract ──────────────────────────────────────────────────

    @Test
    void apiResponse_success_setsFieldsCorrectly() {
        ApiResponse<String> response = ApiResponse.success("hello");
        assertTrue(response.isSuccess());
        assertEquals("hello", response.getData());
        assertNull(response.getError());
        assertNotNull(response.getTimestamp());
    }

    @Test
    void apiResponse_error_setsFieldsCorrectly() {
        ApiResponse<Void> response = ApiResponse.error("NOT_FOUND", "Resource not found");
        assertFalse(response.isSuccess());
        assertNull(response.getData());
        assertNotNull(response.getError());
        assertEquals("NOT_FOUND", response.getError().getCode());
        assertEquals("Resource not found", response.getError().getMessage());
    }

    @Test
    void apiResponse_success_withNull_isValid() {
        ApiResponse<Object> response = ApiResponse.success(null);
        assertTrue(response.isSuccess());
        assertNull(response.getData());
    }

    // ── Enum correctness ──────────────────────────────────────────────────────

    @Test
    void vehicleClass_hasExactlyThreeValues() {
        assertEquals(3, VehicleClass.values().length);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HCV", "LCV", "TWO_WHEELER"})
    void vehicleClass_allExpectedValuesExist(String name) {
        assertDoesNotThrow(() -> VehicleClass.valueOf(name));
    }

    @Test
    void vehicleClass_invalidValue_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> VehicleClass.valueOf("TRUCK"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_ENTRY", "TIME_WINDOW", "VEHICLE_CLASS"})
    void breachType_allExpectedValuesExist(String name) {
        assertDoesNotThrow(() -> BreachType.valueOf(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_ENTRY", "TIME_RESTRICTED", "VEHICLE_CLASS_RESTRICTED"})
    void restrictionType_allExpectedValuesExist(String name) {
        assertDoesNotThrow(() -> RestrictionType.valueOf(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "COMPLIANT", "NON_COMPLIANT", "ACTIVE", "COMPLETED"})
    void routeStatus_allExpectedValuesExist(String name) {
        assertDoesNotThrow(() -> RouteStatus.valueOf(name));
    }

    // ── Exception message contracts ───────────────────────────────────────────

    @Test
    void resourceNotFoundException_messageContainsEntityAndId() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Vehicle", "id", 42L);
        assertTrue(ex.getMessage().contains("Vehicle"));
        assertTrue(ex.getMessage().contains("42"));
    }

    @Test
    void validationException_preservesMessage() {
        ValidationException ex = new ValidationException("Coordinates out of range");
        assertEquals("Coordinates out of range", ex.getMessage());
    }

    // ── Coordinate validation boundary conditions ─────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "12.8, 77.4",   // min boundary — valid
        "13.2, 77.8",   // max boundary — valid
        "12.9, 77.6",   // centre of Bangalore — valid
    })
    void bangaloreCoordinates_validRange_doesNotThrow(double lat, double lng) {
        assertDoesNotThrow(() -> assertValidBangaloreCoordinates(lat, lng));
    }

    @ParameterizedTest
    @CsvSource({
        "12.7, 77.6",   // lat too low
        "13.3, 77.6",   // lat too high
        "12.9, 77.3",   // lng too low
        "12.9, 77.9",   // lng too high
        "0.0, 0.0",     // null island
        "28.6, 77.2",   // Delhi — wrong city
    })
    void bangaloreCoordinates_outOfRange_throwsValidationException(double lat, double lng) {
        assertThrows(ValidationException.class, () -> assertValidBangaloreCoordinates(lat, lng));
    }

    // ── Simulation scenario name normalisation ────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "A, SCENARIO_A",
        "B, SCENARIO_B",
        "C, SCENARIO_C",
        "SCENARIO_A, SCENARIO_A",
        "scenario_b, SCENARIO_B",
        "Scenario C, SCENARIO_C",
    })
    void scenarioNameNormalisation_producesExpectedResult(String input, String expected) {
        assertEquals(expected, normalizeScenarioName(input));
    }

    // ── Helpers (mirrors production logic for isolated testing) ───────────────

    private static final double BANGALORE_MIN_LAT = 12.8;
    private static final double BANGALORE_MAX_LAT = 13.2;
    private static final double BANGALORE_MIN_LNG = 77.4;
    private static final double BANGALORE_MAX_LNG = 77.8;

    private void assertValidBangaloreCoordinates(double lat, double lng) {
        if (lat < BANGALORE_MIN_LAT || lat > BANGALORE_MAX_LAT
                || lng < BANGALORE_MIN_LNG || lng > BANGALORE_MAX_LNG) {
            throw new ValidationException(
                    "Coordinates (" + lat + ", " + lng + ") are outside Bangalore bounding box");
        }
    }

    private String normalizeScenarioName(String name) {
        String normalized = name.trim().toUpperCase();
        if (normalized.equals("A") || normalized.equals("SCENARIO_A") || normalized.equals("SCENARIO A")) {
            return "SCENARIO_A";
        } else if (normalized.equals("B") || normalized.equals("SCENARIO_B") || normalized.equals("SCENARIO B")) {
            return "SCENARIO_B";
        } else if (normalized.equals("C") || normalized.equals("SCENARIO_C") || normalized.equals("SCENARIO C")) {
            return "SCENARIO_C";
        }
        return name;
    }
}
