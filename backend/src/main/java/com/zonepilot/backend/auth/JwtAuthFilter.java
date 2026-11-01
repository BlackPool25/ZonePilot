package com.zonepilot.backend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Validates the Bearer access token and publishes the {@link DepotPrincipal}.
 * Every request re-checks the account: deactivated users and role-changed
 * tokens (demote/promote takes effect immediately) authenticate nothing.
 * DEMO tokens are read-only: non-GET requests get a 403.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AppUserRepository users;

    public JwtAuthFilter(JwtService jwtService, AppUserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                if (!jwtService.isExpired(token) && !jwtService.isRefresh(token)) {
                    Role role = Role.valueOf(jwtService.getRole(token));
                    AppUser user = users.findByEmail(jwtService.getUsername(token)).orElse(null);
                    // Per-request account check: deactivation or a role change
                    // (e.g. OWNER demoted to DISPATCHER) kills old tokens now.
                    if (user != null && Boolean.TRUE.equals(user.getIsActive())
                            && user.getRole() == role) {
                        if (role == Role.DEMO && !"GET".equalsIgnoreCase(request.getMethod())) {
                            response.sendError(403, "Read-only demo token");
                            return;
                        }
                        DepotPrincipal principal = new DepotPrincipal(
                                user.getEmail(), jwtService.getDepotId(token), role);
                        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                }
            } catch (RuntimeException e) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
