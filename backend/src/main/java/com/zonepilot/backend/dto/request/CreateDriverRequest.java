package com.zonepilot.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

public class CreateDriverRequest {

    @NotBlank
    private String name;
    private String phone;
    @NotBlank
    private String licenceNo;
    @NotNull
    private LocalDate licenceExpiry;
    @Pattern(regexp = "PER_TRIP|MONTHLY")
    private String salaryMode = "PER_TRIP";
    @NotNull
    private Long depotId;
    @Email
    @NotBlank
    private String email;
    @NotBlank
    private String password;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getLicenceNo() { return licenceNo; }
    public void setLicenceNo(String licenceNo) { this.licenceNo = licenceNo; }
    public LocalDate getLicenceExpiry() { return licenceExpiry; }
    public void setLicenceExpiry(LocalDate licenceExpiry) { this.licenceExpiry = licenceExpiry; }
    public String getSalaryMode() { return salaryMode; }
    public void setSalaryMode(String salaryMode) { this.salaryMode = salaryMode; }
    public Long getDepotId() { return depotId; }
    public void setDepotId(Long depotId) { this.depotId = depotId; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
