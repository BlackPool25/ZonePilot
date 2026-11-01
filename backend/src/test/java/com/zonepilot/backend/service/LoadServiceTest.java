package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.LoadEvent;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.LoadSource;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.LoadEventRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoadServiceTest {

    @Mock LoadEventRepository events;
    @Mock VehicleRepository vehicles;
    @InjectMocks LoadService loads;

    @Test
    void record_chainsRunningTotalFromLatest() {
        Vehicle v = new Vehicle();
        v.setId(7L);
        when(vehicles.findById(7L)).thenReturn(Optional.of(v));
        LoadEvent last = new LoadEvent();
        last.setRunningTotalKg(500.0);
        when(events.findTopByVehicleIdOrderByRecordedAtDescIdDesc(7L)).thenReturn(Optional.of(last));
        when(events.save(any())).thenAnswer(i -> i.getArgument(0));

        LoadEvent saved = loads.record(7L, 3L, 9L, 200.0, LoadSource.DRIVER_APP);

        ArgumentCaptor<LoadEvent> cap = ArgumentCaptor.forClass(LoadEvent.class);
        verify(events).save(cap.capture());
        assertEquals(700.0, cap.getValue().getRunningTotalKg());
        assertEquals(200.0, saved.getDeltaKg());
    }

    @Test
    void currentLoad_emptyIsZero() {
        when(events.findTopByVehicleIdOrderByRecordedAtDescIdDesc(1L)).thenReturn(Optional.empty());
        assertEquals(0.0, loads.currentLoad(1L));
    }

    @Test
    void checkCapacity_throwsNamingExcess() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> LoadService.checkCapacity(1000.0, 1300.0, "KA05-AA-1001"));
        assertTrue(ex.getMessage().contains("by 300 kg"));
        assertTrue(ex.getMessage().contains("KA05-AA-1001"));
    }

    @Test
    void checkCapacity_underOrUnknownPasses() {
        assertDoesNotThrow(() -> LoadService.checkCapacity(1000.0, 800.0, "KA05-AA-1001"));
        assertDoesNotThrow(() -> LoadService.checkCapacity(null, 5000.0, "KA05-AA-1001"));
    }
}
