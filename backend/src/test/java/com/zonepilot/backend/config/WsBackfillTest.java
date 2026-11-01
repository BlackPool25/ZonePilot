package com.zonepilot.backend.config;

import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.auth.JwtService;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.repository.DriverRepository;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.repository.GroupDriverRepository;
import com.zonepilot.backend.service.TickBroadcaster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WS reconnect backfill: an authorized SUBSCRIBE to /topic/group/{id} pushes
 * the buffered ticks to the subscriber's private queue
 * (/user/queue/ticks-backfill) so a reconnecting client replays missed ticks.
 */
@ExtendWith(MockitoExtension.class)
class WsBackfillTest {

    @Mock FleetGroupRepository groups;
    @Mock GroupScope scope;
    @Mock SimpMessagingTemplate template;

    private WsAuthInterceptor interceptor(TickBroadcaster broadcaster) {
        return new WsAuthInterceptor(
                mock(JwtService.class), groups, scope,
                mock(AppUserRepository.class), mock(DriverRepository.class),
                mock(GroupDriverRepository.class),
                mock(SubscribableChannel.class), template, broadcaster);
    }

    private Message<?> subscribe(String dest, DepotPrincipal principal) {
        StompHeaderAccessor h = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        h.setDestination(dest);
        h.setSessionId("s1");
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("zp-principal", principal);
        h.setSessionAttributes(attrs);
        h.setUser(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        return MessageBuilder.createMessage(new byte[0], h.getMessageHeaders());
    }

    @Test
    void subscribe_groupTopic_pushesBufferedTicksToPrivateQueue() {
        FleetGroup g = new FleetGroup();
        g.setId(7L);
        when(groups.findById(7L)).thenReturn(Optional.of(g));
        TickBroadcaster broadcaster = mock(TickBroadcaster.class);
        List<Map<String, Object>> buffered = List.of(Map.of(
                "type", "tick", "groupId", 7L, "vehicleId", 1L));
        when(broadcaster.recentTicks(eq(7L), isNull(), anyInt())).thenReturn(buffered);
        DepotPrincipal principal = new DepotPrincipal("o@zp.test", null, Role.OWNER);

        Message<?> out = interceptor(broadcaster).preSend(
                subscribe("/topic/group/7", principal),
                mock(MessageChannel.class));

        assertNotNull(out);
        // NOTE: SimpMessagingTemplate prepends "/user" to the destination.
        verify(template).convertAndSendToUser(
                eq(principal.toString()), eq("/queue/ticks-backfill"), eq(buffered));
    }

    @Test
    void subscribe_noBufferedTicks_noPush() {
        FleetGroup g = new FleetGroup();
        g.setId(7L);
        when(groups.findById(7L)).thenReturn(Optional.of(g));
        TickBroadcaster broadcaster = mock(TickBroadcaster.class);
        when(broadcaster.recentTicks(eq(7L), isNull(), anyInt())).thenReturn(List.of());
        DepotPrincipal principal = new DepotPrincipal("o@zp.test", null, Role.OWNER);

        Message<?> out = interceptor(broadcaster).preSend(
                subscribe("/topic/group/7", principal),
                mock(MessageChannel.class));

        assertNotNull(out);
        verify(template, never()).convertAndSendToUser(anyString(), anyString(), any());
    }
}
