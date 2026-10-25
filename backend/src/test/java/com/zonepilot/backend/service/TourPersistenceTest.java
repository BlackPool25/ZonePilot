package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.*;
import com.zonepilot.backend.it.SliceIT;
import com.zonepilot.backend.repository.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 1 RED: tour + ordered stops with full manifest persist and read back.
 */
class TourPersistenceTest extends SliceIT {

    @Autowired
    TourRepository tours;
    @Autowired
    TourStopRepository stops;
    @Autowired
    FleetGroupRepository groups;
    @Autowired
    DriverRepository drivers;

    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);

    private Tour savedTour() {
        FleetGroup g = new FleetGroup();
        g.setName("TourSpec " + System.nanoTime());
        g.setActive(true);
        groups.save(g);

        Driver d = new Driver();
        d.setName("Tour Driver");
        d.setLicenceNo("LIC-TOUR-" + System.nanoTime());
        d.setSalaryMode(com.zonepilot.backend.enums.SalaryMode.PER_TRIP);
        d.setIsActive(true);
        drivers.save(d);

        Tour t = new Tour();
        t.setGroup(g);
        t.setVehicle(saveVehicle("KA09-TOUR-" + (System.nanoTime() % 100000)));
        t.setDriver(d);
        t.setStatus(TourStatus.ACTIVE);
        return tours.save(t);
    }

    private TourStop stop(Tour t, int seq, double lng) {
        TourStop s = new TourStop();
        s.setTour(t);
        s.setSeq(seq);
        s.setPoint(gf.createPoint(new Coordinate(lng, 12.93)));
        s.setAddress(seq + " Main St");
        s.setQuantity(10 + seq);
        s.setWeightKg(25.5);
        s.setRecipientName("Recipient " + seq);
        s.setRecipientPhone("988600000" + seq);
        s.setWindowStart(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        s.setWindowEnd(Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS));
        s.setCodAmount(new java.math.BigDecimal("150.00"));
        s.setHandlingNote("fragile");
        s.setStatus(StopStatus.PENDING);
        return stops.save(s);
    }

    @Test
    void stops_persistInOrder_withFullManifest() {
        Tour t = savedTour();
        stop(t, 1, 77.62);
        stop(t, 0, 77.61);

        List<TourStop> ordered = stops.findByTourIdOrderBySeqAsc(t.getId());
        assertThat(ordered).hasSize(2);
        assertThat(ordered.get(0).getSeq()).isZero();
        assertThat(ordered.get(0).getAddress()).isEqualTo("0 Main St");
        assertThat(ordered.get(0).getQuantity()).isEqualTo(10);
        assertThat(ordered.get(0).getWeightKg()).isEqualTo(25.5);
        assertThat(ordered.get(0).getRecipientName()).isEqualTo("Recipient 0");
        assertThat(ordered.get(0).getRecipientPhone()).isEqualTo("9886000000");
        assertThat(ordered.get(0).getCodAmount()).isEqualByComparingTo("150.00");
        assertThat(ordered.get(0).getHandlingNote()).isEqualTo("fragile");
        assertThat(ordered.get(0).getStatus()).isEqualTo(StopStatus.PENDING);
    }

    @Test
    void duplicateSeq_rejected() {
        Tour t = savedTour();
        stop(t, 0, 77.61);
        assertThatThrownBy(() -> stop(t, 0, 77.62))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void route_canLinkTour() {
        Tour t = savedTour();
        assertThat(tours.findById(t.getId())).isPresent();
    }
}
