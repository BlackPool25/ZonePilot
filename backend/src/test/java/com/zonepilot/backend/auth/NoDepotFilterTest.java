package com.zonepilot.backend.auth;

import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.Vehicle;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Single-fleet model: no depot partition anymore. No entity may declare
 * the old {@code depotFilter}, and no {@code @FilterDef} may remain.
 */
class NoDepotFilterTest {

    @Test
    void noEntity_declaresDepotFilter() {
        assertThat(Vehicle.class.getAnnotation(Filter.class)).isNull();
        assertThat(Driver.class.getAnnotation(Filter.class)).isNull();
        assertThat(com.zonepilot.backend.entity.Vehicle.class.getPackage()
                .getAnnotation(FilterDef.class)).isNull();
    }

    @Test
    void depotRepository_isGone() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> Class.forName("com.zonepilot.backend.repository.DepotRepository"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
