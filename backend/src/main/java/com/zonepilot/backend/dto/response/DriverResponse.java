package com.zonepilot.backend.dto.response;

import com.zonepilot.backend.entity.Driver;

public class DriverResponse {

    private Long id;
    private String name;
    private String phone;
    private String licenceNo;
    private String licenceExpiry;
    private String salaryMode;
    private Boolean isActive;

    public static DriverResponse from(Driver d) {
        DriverResponse r = new DriverResponse();
        r.id = d.getId();
        r.name = d.getName();
        r.phone = d.getPhone();
        r.licenceNo = d.getLicenceNo();
        r.licenceExpiry = d.getLicenceExpiry() == null ? null : d.getLicenceExpiry().toString();
        r.salaryMode = d.getSalaryMode().name();
        r.isActive = d.getIsActive();
        return r;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getLicenceNo() { return licenceNo; }
    public String getLicenceExpiry() { return licenceExpiry; }
    public String getSalaryMode() { return salaryMode; }
    public Boolean getIsActive() { return isActive; }
}
