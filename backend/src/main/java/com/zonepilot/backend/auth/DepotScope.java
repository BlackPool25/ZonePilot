package com.zonepilot.backend.auth;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Enables the Hibernate {@code depotFilter} from the JWT principal inside
 * {@code @Transactional} service methods. OWNER sees all depots.
 */
@Component
public class DepotScope {

    public static final String FILTER_NAME = "depotFilter";
    public static final String PARAM_DEPOT_ID = "depotId";

    private final EntityManager entityManager;

    public DepotScope(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public static DepotPrincipal current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof DepotPrincipal p) {
            return p;
        }
        return null;
    }

    public void apply() {
        Session session = entityManager.unwrap(Session.class);
        DepotPrincipal p = current();
        if (p == null || p.isOwner() || p.depotId() == null) {
            session.disableFilter(FILTER_NAME);
            return;
        }
        session.enableFilter(FILTER_NAME).setParameter(PARAM_DEPOT_ID, p.depotId());
    }
}
