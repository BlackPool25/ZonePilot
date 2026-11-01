package com.zonepilot.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

public class SaveDocumentRequest {

    @NotNull
    private Long vehicleId;
    @NotBlank
    @Pattern(regexp = "FITNESS|PERMIT|INSURANCE|PUC")
    private String type;
    @NotBlank
    private String docNo;
    @NotNull
    private LocalDate expiryDate;

    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDocNo() { return docNo; }
    public void setDocNo(String docNo) { this.docNo = docNo; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
}
