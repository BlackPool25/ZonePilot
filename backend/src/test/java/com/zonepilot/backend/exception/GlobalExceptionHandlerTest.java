package com.zonepilot.backend.exception;

import com.zonepilot.backend.dto.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void resourceNotFound_returns404() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Vehicle", "id", 1L);
        ResponseEntity<ApiResponse<Void>> response = handler.handleResourceNotFound(ex);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertEquals("RESOURCE_NOT_FOUND", response.getBody().getError().getCode());
    }

    @Test
    void validationException_returns400() {
        ValidationException ex = new ValidationException("bad input");
        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(ex);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_ERROR", response.getBody().getError().getCode());
    }

    @Test
    void routingException_returns422() {
        RoutingException ex = new RoutingException("no path found");
        ResponseEntity<ApiResponse<Void>> response = handler.handleRouting(ex);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertEquals("ROUTING_ERROR", response.getBody().getError().getCode());
    }

    @Test
    void simulationException_returns400() {
        SimulationException ex = new SimulationException("scenario not found");
        ResponseEntity<ApiResponse<Void>> response = handler.handleSimulation(ex);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("SIMULATION_ERROR", response.getBody().getError().getCode());
    }

    @Test
    void dataIntegrityViolation_returns409() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("duplicate key");
        ResponseEntity<ApiResponse<Void>> response = handler.handleDataIntegrity(ex);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("DATA_INTEGRITY_VIOLATION", response.getBody().getError().getCode());
    }

    @Test
    void illegalArgumentException_returns400() {
        // S2 fix: invalid enum value should return 400, not 500
        IllegalArgumentException ex = new IllegalArgumentException("No enum constant VehicleClass.TRUCK");
        ResponseEntity<ApiResponse<Void>> response = handler.handleIllegalArgument(ex);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_VALUE", response.getBody().getError().getCode());
    }

    @Test
    void genericException_returns500() {
        Exception ex = new RuntimeException("unexpected");
        ResponseEntity<ApiResponse<Void>> response = handler.handleGeneric(ex);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("INTERNAL_ERROR", response.getBody().getError().getCode());
    }

    @Test
    void methodArgumentNotValid_returns400WithFieldErrors() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("request", "lat", "must not be null");
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

        ResponseEntity<ApiResponse<Map<String, String>>> response = handler.handleValidationErrors(ex);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_ERROR", response.getBody().getError().getCode());
    }

    @Test
    void malformedJson_returns400() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error", new MockHttpInputMessage(new byte[0]));
        ResponseEntity<ApiResponse<Void>> response = handler.handleNotReadable(ex);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("MALFORMED_JSON", response.getBody().getError().getCode());
    }

    @Test
    void unsupportedMediaType_returns415() {
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException(
                MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnsupportedMediaType(ex);
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, response.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", response.getBody().getError().getCode());
    }

    @Test
    void methodNotSupported_returns405() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("GET", List.of("POST"));
        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodNotSupported(ex);
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals("METHOD_NOT_ALLOWED", response.getBody().getError().getCode());
    }

    @Test
    void noResourceFound_returns404() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/api/nonexistent");
        ResponseEntity<ApiResponse<Void>> response = handler.handleNoResourceFound(ex);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("ENDPOINT_NOT_FOUND", response.getBody().getError().getCode());
    }

    @Test
    void missingRequestParam_returns400() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("name", "String");
        ResponseEntity<ApiResponse<Void>> response = handler.handleMissingParam(ex);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("MISSING_PARAMETER", response.getBody().getError().getCode());
    }

    @Test
    void methodArgumentTypeMismatch_returns400() {
        MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
        when(ex.getName()).thenReturn("id");
        when(ex.getValue()).thenReturn("abc");
        when(ex.getRequiredType()).thenAnswer(inv -> Long.class);

        ResponseEntity<ApiResponse<Void>> response = handler.handleTypeMismatch(ex);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_PARAMETER_TYPE", response.getBody().getError().getCode());
        assertTrue(response.getBody().getError().getMessage().contains("abc"));
        assertTrue(response.getBody().getError().getMessage().contains("id"));
    }
}
