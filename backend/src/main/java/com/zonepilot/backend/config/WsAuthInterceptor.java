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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Task 3 scoped WS: JWT on the STOMP CONNECT frame, per-group SUBSCRIBE
 * enforcement. Anonymous subscribes are rejected; OWNER passes every group,
 * a manager passes only groups they manage (same rule as the REST scope).
 */
@Component
public class WsAuthInterceptor implements ChannelInterceptor {

    static final String PRINCIPAL_ATTR = "zp-principal";

    /** SEND allow-list: no /app handlers exist yet — everything rejects. */
    private static final java.util.Set<String> ALLOWED_APP_DESTS = java.util.Set.of();

    private final JwtService jwt;
    private final FleetGroupRepository groups;
    private final GroupScope scope;
    private final AppUserRepository appUsers;
    private final DriverRepository drivers;
    private final GroupDriverRepository groupDrivers;
    private final SubscribableChannel outbound;
    private final SimpMessagingTemplate template;
    private final TickBroadcaster ticks;

    public WsAuthInterceptor(JwtService jwt, FleetGroupRepository groups, GroupScope scope,
                             AppUserRepository appUsers, DriverRepository drivers,
                             GroupDriverRepository groupDrivers,
                             @Lazy @Qualifier("clientOutboundChannel") SubscribableChannel outbound,
                             @Lazy SimpMessagingTemplate template, @Lazy TickBroadcaster ticks) {
        this.jwt = jwt;
        this.groups = groups;
        this.scope = scope;
        this.appUsers = appUsers;
        this.drivers = drivers;
        this.groupDrivers = groupDrivers;
        this.outbound = outbound;
        this.template = template;
        this.ticks = ticks;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        if (accessor.getCommand() == StompCommand.CONNECT) {
            String header = accessor.getFirstNativeHeader("Authorization");
            DepotPrincipal principal = principalFrom(header);
            if (principal != null) {
                accessor.setUser(new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))));
                Map<String, Object> attrs = accessor.getSessionAttributes();
                if (attrs != null) {
                    attrs.put(PRINCIPAL_ATTR, principal);
                }
            } else if (header != null) {
                // Bad token presented: fail the CONNECT fast instead of
                // letting an anonymous session linger to SUBSCRIBE time.
                return reject(accessor, "Invalid token");
            }
            return message;
        }
        if (accessor.getCommand() == StompCommand.SEND) {
            // No /app handlers are registered (see WsConfig): every SEND is an
            // unknown destination. The allow-list stays explicit so adding a
            // handler is a one-line change, not a bypass.
            if (!ALLOWED_APP_DESTS.contains(accessor.getDestination())) {
                return reject(accessor, "Unknown destination");
            }
            return message;
        }
        if (accessor.getCommand() == StompCommand.SUBSCRIBE) {            String dest = accessor.getDestination();
            if (dest != null && dest.startsWith("/topic/")) {
                Map<String, Object> attrs = accessor.getSessionAttributes();
                DepotPrincipal principal = attrs == null ? null
                        : (DepotPrincipal) attrs.get(PRINCIPAL_ATTR);
                // Throwing here is swallowed by the async inbound channel, so
                // reject explicitly with an ERROR frame and drop the SUBSCRIBE.
                if (principal == null) {
                    return reject(accessor, "Login required");
                }
                if (!dest.startsWith("/topic/group/")) {
                    // Only per-group ticks exist; anything else is owner-only.
                    if (!principal.isOwner()) {
                        return reject(accessor, "Forbidden destination");
                    }
                    return message;
                }
                Long groupId = groupIdOf(dest);
                FleetGroup group = groupId == null ? null
                        : groups.findById(groupId).orElse(null);
                if (group == null) {
                    return reject(accessor, "No such group");
                }
                try {
                    scope.requireGroupAccess(principal, group);
                } catch (AccessDeniedException e) {
                    // Task 4: drivers own no groups but may listen to groups
                    // they are dispatched in.
                    if (principal.role() == Role.DRIVER && isGroupDriver(principal, groupId)) {
                        pushBackfill(accessor, groupId);
                        return message;
                    }
                    return reject(accessor, e.getMessage());
                }
                pushBackfill(accessor, groupId);
            }
            return message;
        }
        return message;
    }

    /** Reconnect backfill: buffered ticks go to the subscriber's private
     * queue (/user/queue/ticks-backfill) — a broadcast to /topic/group/{id}
     * here would re-fan-out to every subscriber via the simple broker. */
    private void pushBackfill(StompHeaderAccessor accessor, Long groupId) {
        if (accessor.getUser() == null) {
            return;
        }
        List<Map<String, Object>> missed =
                ticks.recentTicks(groupId, null, TickBroadcaster.BACKFILL_PUSH);
        if (missed.isEmpty()) {
            return;
        }
        template.convertAndSendToUser(
                accessor.getUser().getName(), "/queue/ticks-backfill", missed);
    }

    /** Group id prefix; trailing sub-paths (e.g. /topic/group/7/ticks) accepted. */
    private static Long groupIdOf(String dest) {
        try {
            String rest = dest.substring("/topic/group/".length());
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                rest = rest.substring(0, slash);
            }
            return Long.parseLong(rest);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** DRIVER login mapped to roster → group member may subscribe. */
    private boolean isGroupDriver(DepotPrincipal principal, Long groupId) {
        return appUsers.findByEmail(principal.username())
                .flatMap(u -> drivers.findByUserId(u.getId()))
                .map(d -> groupDrivers.existsByGroupIdAndDriverId(groupId, d.getId()))
                .orElse(false);
    }

    /** ERROR frame to the session; null return drops the rejected SUBSCRIBE. */
    private Message<?> reject(StompHeaderAccessor accessor, String reason) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.ERROR);
        headers.setSessionId(accessor.getSessionId());
        headers.setMessage(reason);
        outbound.send(MessageBuilder.createMessage(
                reason.getBytes(StandardCharsets.UTF_8), headers.getMessageHeaders()));
        return null;
    }

    private DepotPrincipal principalFrom(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring(7);
        try {
            if (jwt.isExpired(token) || jwt.isRefresh(token)) {
                return null;
            }
            return new DepotPrincipal(jwt.getUsername(token), jwt.getDepotId(token),
                    Role.valueOf(jwt.getRole(token)));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
