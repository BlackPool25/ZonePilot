package com.zonepilot.backend.controller;

import com.zonepilot.backend.auth.AuthService;
import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.DepotScope;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.dto.request.LoginRequest;
import com.zonepilot.backend.dto.request.RefreshRequest;
import com.zonepilot.backend.dto.request.RegisterRequest;
import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.dto.response.MeResponse;
import com.zonepilot.backend.dto.response.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Slice 1 authentication endpoints")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a user (first user bootstraps OWNER; afterwards OWNER-only)")
    public ResponseEntity<ApiResponse<TokenResponse>> register(@Valid @RequestBody RegisterRequest request) {
        AuthService.TokenPair pair = authService.register(
                request.getEmail(), request.getPassword(),
                Role.valueOf(request.getRole()));
        return ResponseEntity.ok(ApiResponse.success(new TokenResponse(pair.accessToken(), pair.refreshToken())));
    }

    @PostMapping("/login")
    @Operation(summary = "Login with email + password")
    public ResponseEntity<ApiResponse<TokenResponse>> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        // Client IP feeds the per-IP throttle bucket (see LoginThrottleService).
        AuthService.TokenPair pair = authService.login(
                request.getEmail(), request.getPassword(), http.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.success(new TokenResponse(pair.accessToken(), pair.refreshToken())));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate refresh token for a new access token")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        AuthService.TokenPair pair = authService.refresh(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success(new TokenResponse(pair.accessToken(), pair.refreshToken())));
    }

    @GetMapping("/me")
    @Operation(summary = "Current caller identity from JWT")
    public ResponseEntity<ApiResponse<MeResponse>> me() {
        DepotPrincipal p = DepotScope.current();
        MeResponse me = new MeResponse();
        me.setEmail(p.username());
        me.setRole(p.role().name());
        return ResponseEntity.ok(ApiResponse.success(me));
    }
}
