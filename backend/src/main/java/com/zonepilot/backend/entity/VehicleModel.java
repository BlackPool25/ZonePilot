package com.zonepilot.backend.entity;

import com.zonepilot.backend.enums.VehicleClass;
import jakarta.persistence.*;

@Entity
@Table(name = "vehicle_model")
public class VehicleModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String code;

    @Column(nullable = false, length = 60)
    private String make;

    @Column(nullable = false, length = 80)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_class", nullable = false, length = 20)
    private VehicleClass vehicleClass;

    @Column(name = "gvw_kg", nullable = false)
    private Double gvwKg;

    @Column(name = "payload_kg", nullable = false)
    private Double payloadKg;

    @Column(name = "fuel_type", nullable = false, length = 20)
    private String fuelType;

    @Column(name = "rated_mileage_kmpl")
    private Double ratedMileageKmpl;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getMake() { return make; }
    public void setMake(String make) { this.make = make; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public VehicleClass getVehicleClass() { return vehicleClass; }
    public void setVehicleClass(VehicleClass vehicleClass) { this.vehicleClass = vehicleClass; }
    public Double getGvwKg() { return gvwKg; }
    public void setGvwKg(Double gvwKg) { this.gvwKg = gvwKg; }
    public Double getPayloadKg() { return payloadKg; }
    public void setPayloadKg(Double payloadKg) { this.payloadKg = payloadKg; }
    public String getFuelType() { return fuelType; }
    public void setFuelType(String fuelType) { this.fuelType = fuelType; }
    public Double getRatedMileageKmpl() { return ratedMileageKmpl; }
    public void setRatedMileageKmpl(Double ratedMileageKmpl) { this.ratedMileageKmpl = ratedMileageKmpl; }
}
