package com.zonepilot.backend.auth;

import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.Vehicle;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Depot scoping: exactly one {@code @FilterDef} (on the entity package),
 * {@code @Filter} on each depot-scoped entity, auto-enabled from JWT via DepotScope.
 */
class DepotFilterWiringTest {

    @Test
    void filterDef_declaredOnceOnPackage() {
        FilterDef def = Vehicle.class.getPackage().getAnnotation(FilterDef.class);
        assertThat(def).isNotNull();
        assertThat(def.name()).isEqualTo(DepotScope.FILTER_NAME);

        assertThat(Vehicle.class.getAnnotation(FilterDef.class)).isNull();
        assertThat(Driver.class.getAnnotation(FilterDef.class)).isNull();
    }

    @Test
    void depotScopedEntities_declareFilter() {
        for (Class<?> entity : new Class<?>[] { Vehicle.class, Driver.class }) {
            Filter filter = entity.getAnnotation(Filter.class);
            assertThat(filter).as(entity.getSimpleName()).isNotNull();
            assertThat(filter.name()).isEqualTo(DepotScope.FILTER_NAME);
            assertThat(filter.condition()).contains("depot_id");
        }
    }
}
