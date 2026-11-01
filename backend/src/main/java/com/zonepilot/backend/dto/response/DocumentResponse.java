package com.zonepilot.backend.dto.response;

import com.zonepilot.backend.entity.VehicleDocument;

public class DocumentResponse {

    private Long id;
    private Long vehicleId;
    private String registrationNumber;
    private String type;
    private String docNo;
    private String expiryDate;

    public static DocumentResponse from(VehicleDocument d) {
        DocumentResponse r = new DocumentResponse();
        r.id = d.getId();
        r.vehicleId = d.getVehicle().getId();
        r.registrationNumber = d.getVehicle().getRegistrationNumber();
        r.type = d.getType().name();
        r.docNo = d.getDocNo();
        r.expiryDate = d.getExpiryDate().toString();
        return r;
    }

    public Long getId() { return id; }
    public Long getVehicleId() { return vehicleId; }
    public String getRegistrationNumber() { return registrationNumber; }
    public String getType() { return type; }
    public String getDocNo() { return docNo; }
    public String getExpiryDate() { return expiryDate; }
}
