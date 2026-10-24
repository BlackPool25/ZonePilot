package com.zonepilot.backend.dto.response;

public class MeResponse {

    private String email;
    private String role;
    private Long depotId;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Long getDepotId() { return depotId; }
    public void setDepotId(Long depotId) { this.depotId = depotId; }
}
