package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.LoadEvent;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.LoadSource;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.LoadEventRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoadService {

    private final LoadEventRepository events;
    private final VehicleRepository vehicles;

    public LoadService(LoadEventRepository events, VehicleRepository vehicles) {
        this.events = events;
        this.vehicles = vehicles;
    }

    /** Records one load (+kg) / unload (-kg) event; running total chains from the latest event. */
    @Transactional
    public LoadEvent record(Long vehicleId, Long tourId, Long stopId, double deltaKg, LoadSource source) {
        Vehicle vehicle = vehicles.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", "id", vehicleId));
        double before = currentLoad(vehicleId);
        LoadEvent e = new LoadEvent();
        e.setVehicle(vehicle);
        e.setTourId(tourId);
        e.setStopId(stopId);
        e.setDeltaKg(deltaKg);
        e.setRunningTotalKg(before + deltaKg);
        e.setSource(source);
        return events.save(e);
    }

    @Transactional(readOnly = true)
    public double currentLoad(Long vehicleId) {
        return events.findTopByVehicleIdOrderByRecordedAtDescIdDesc(vehicleId)
                .map(LoadEvent::getRunningTotalKg).orElse(0.0);
    }

    /** Hard block: planned tour load must fit the model's rated payload. */
    public static void checkCapacity(Double payloadKg, double plannedKg, String registrationNumber) {
        if (payloadKg == null) return; // no model linked: track only
        if (plannedKg > payloadKg) {
            throw new ValidationException(String.format(
                    "tour load %.0f kg exceeds vehicle %s capacity %.0f kg by %.0f kg",
                    plannedKg, registrationNumber, payloadKg, plannedKg - payloadKg));
        }
    }
}
