package com.zonepilot.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public class CreateSlipRequest {

    @NotBlank
    @Pattern(regexp = "FUEL|TOLL")
    private String kind;

    @Positive
    private double amount;

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
}
