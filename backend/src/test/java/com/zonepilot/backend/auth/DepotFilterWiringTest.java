package com.zonepilot.backend.auth;

import com.zonepilot.backend.entity.Vehicle;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Slice 1: depot scoping is declared on the entity (auto-enabled from JWT via DepotScope). */
class DepotFilterWiringTest {

    @Test
    void vehicle_declaresDepotFilter() {
        FilterDef def = Vehicle.class.getAnnotation(FilterDef.class);
        assertThat(def).isNotNull();
        assertThat(def.name()).isEqualTo(DepotScope.FILTER_NAME);

        Filter filter = Vehicle.class.getAnnotation(Filter.class);
        assertThat(filter).isNotNull();
        assertThat(filter.name()).isEqualTo(DepotScope.FILTER_NAME);
        assertThat(filter.condition()).contains("depot_id");
    }
}
