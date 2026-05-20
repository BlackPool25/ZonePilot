package com.zonepilot.backend.dto.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public class StartSimulationRequest {

    @NotEmpty(message = "At least one scenario is required")
    private List<String> scenarios;

    public List<String> getScenarios() { return scenarios; }
    public void setScenarios(List<String> scenarios) { this.scenarios = scenarios; }
}
