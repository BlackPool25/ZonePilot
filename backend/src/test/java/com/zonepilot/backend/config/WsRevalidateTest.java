package com.zonepilot.backend.config;

import com.zonepilot.backend.auth.AppUser;
import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.JwtService;
import com.zonepilot.backend.auth.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RED: WS must revalidate the token on every SUBSCRIBE (expiry + isActive +
 * role), not just trust the CONNECT-time principal.
 */
@ExtendWith(MockitoExtension.class)
class WsRevalidateTest {

    static final String SECRET = "test-secret-32-bytes-long-xxxxxxxx";

    @Mock AppUserRepository appUsers;

    private WsAuthInterceptor interceptor(JwtService jwt) {
        return new WsAuthInterceptor(jwt, mock(com.zonepilot.backend.repository.FleetGroupRepository.class),
                mock(com.zonepilot.backend.auth.GroupScope.class), appUsers,
                mock(com.zonepilot.backend.repository.DriverRepository.class),
                mock(com.zonepilot.backend.repository.GroupDriverRepository.class),
                mock(org.springframework.messaging.SubscribableChannel.class),
                mock(org.springframework.messaging.simp.SimpMessagingTemplate.class),
                mock(com.zonepilot.backend.service.TickBroadcaster.class));
    }

    @Test
    void revalidate_deactivatedUser_null() {
        JwtService jwt = new JwtService(SECRET, Duration.ofMinutes(15), Duration.ofDays(7));
        String token = jwt.generateAccessToken("off@zp.test", null, "OWNER");
        AppUser u = new AppUser();
        u.setEmail("off@zp.test");
        u.setRole(Role.OWNER);
        u.setIsActive(false);
        when(appUsers.findByEmail("off@zp.test")).thenReturn(Optional.of(u));
        assertNull(interceptor(jwt).revalidate(token));
    }

    @Test
    void revalidate_roleChanged_null() {
        JwtService jwt = new JwtService(SECRET, Duration.ofMinutes(15), Duration.ofDays(7));
        String token = jwt.generateAccessToken("dem@zp.test", null, "OWNER");
        AppUser u = new AppUser();
        u.setEmail("dem@zp.test");
        u.setRole(Role.DISPATCHER);
        u.setIsActive(true);
        when(appUsers.findByEmail("dem@zp.test")).thenReturn(Optional.of(u));
        assertNull(interceptor(jwt).revalidate(token));
    }

    @Test
    void revalidate_valid_returnsPrincipal() {
        JwtService jwt = new JwtService(SECRET, Duration.ofMinutes(15), Duration.ofDays(7));
        String token = jwt.generateAccessToken("ok@zp.test", null, "DISPATCHER");
        AppUser u = new AppUser();
        u.setEmail("ok@zp.test");
        u.setRole(Role.DISPATCHER);
        u.setIsActive(true);
        when(appUsers.findByEmail("ok@zp.test")).thenReturn(Optional.of(u));
        DepotPrincipal p = interceptor(jwt).revalidate(token);
        assertNotNull(p);
        assertEquals(Role.DISPATCHER, p.role());
    }
}
